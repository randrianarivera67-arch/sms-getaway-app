package mg.smsgateway.service;

import android.content.Context;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * File d'attente STRICTEMENT SEQUENTIELLE des retraits USSD.
 *
 * <p>Pourquoi c'est indispensable :
 * <ul>
 *   <li>Une session USSD est <b>unique par SIM</b>. Deux compositions simultanees
 *       ne coexistent pas : la seconde annule ou fait echouer la premiere.</li>
 *   <li>{@link UssdAccessibilityService} garde un etat <b>statique global</b>
 *       (PIN arme, retrait en cours, texte lu). Armer un second retrait pendant
 *       qu'un premier est en cours ECRASE cet etat : le PIN du retrait B pouvait
 *       etre tape dans la boite du retrait A, et le texte de B etre attribue a A.
 *       Concretement : de l'argent envoye au mauvais numero ou au mauvais montant.</li>
 * </ul>
 *
 * <p>Regles appliquees ici :
 * <ol>
 *   <li><b>Un seul</b> retrait traite a la fois, sur tout le telephone.</li>
 *   <li><b>FIFO</b> : premier arrive, premier servi.</li>
 *   <li><b>Pause</b> de {@link #GAP_MS} entre deux retraits (demande metier :
 *       1 a 2 secondes), pour laisser l'operateur clore la session precedente.</li>
 *   <li><b>Anti-doublon</b> : un retraitId deja en file ou en cours est ignore.</li>
 *   <li><b>Anti-blocage</b> : chaque tache a un delai de garde. Si le moteur ne
 *       rappelle jamais, la file repart au lieu de se figer pour toujours.</li>
 * </ol>
 *
 * <p>Cette classe ne compose jamais elle-meme : elle delegue a {@link UssdEngine}.
 */
public final class UssdQueue {

    private static final String TAG = "UssdQueue";

    /** Pause entre deux retraits (ms). */
    private static final long GAP_MS = 1500L;

    /**
     * Delai de garde par tache. Doit rester SUPERIEUR au delai interne du moteur
     * (25 s en mode interactif) afin de ne se declencher que si le moteur est
     * reellement muet — jamais pour doubler un resultat legitime.
     */
    private static final long WATCHDOG_MS = 45_000L;

    /** Ce qu'il faut executer pour un retrait. */
    public static final class Job {
        public final String retraitId;
        public final String ussdCode;
        public final String operator;
        /** Vide => PIN deja inclus dans ussdCode (MVola) ; sinon saisie a l'invite (Orange). */
        public final String ussdPin;
        /** Reponse a taper sur un ecran de saisie qui n'est PAS une demande de PIN. */
        public final String menuReply;
        /** Nombre maximum d'ecrans de saisie a traiter (Orange en demande 2). */
        public final int maxSteps;
        public final UssdEngine.UssdCallback callback;

        public Job(String retraitId, String ussdCode, String operator, String ussdPin,
                   String menuReply, int maxSteps, UssdEngine.UssdCallback callback) {
            this.retraitId = retraitId;
            this.ussdCode  = ussdCode;
            this.operator  = operator;
            this.ussdPin   = ussdPin  == null ? "" : ussdPin.trim();
            this.menuReply = menuReply == null ? "" : menuReply.trim();
            this.maxSteps  = maxSteps < 1 ? 1 : maxSteps;
            this.callback  = callback;
        }
    }

    /**
     * Duree pendant laquelle un retrait deja traite reste refuse.
     * Protection contre une double livraison serveur : mieux vaut ignorer un
     * ordre legitime que de payer deux fois le meme client.
     */
    private static final long MEMOIRE_MS = 30 * 60 * 1000L;

    private static final Deque<Job>  FILE      = new ArrayDeque<>();
    /** retraitId -> instant de fin. Sert d'anti-doublon a memoire longue. */
    private static final java.util.Map<String, Long> DEJA_VUS = new java.util.HashMap<>();
    private static final Set<String> CONNUS    = new HashSet<>();
    private static final Object      VERROU    = new Object();

    private static boolean enCours     = false;
    private static String  idEnCours   = null;
    private static Context appContext  = null;

    private static final android.os.Handler H =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private UssdQueue() { }

    /**
     * Ajoute un retrait en fin de file. Ne compose rien immediatement.
     * @return false si ce retrait est deja en file ou en cours (doublon ignore).
     */
    public static boolean enqueue(Context context, Job job) {
        if (job == null || job.retraitId == null || job.retraitId.isEmpty()
                || job.ussdCode == null || job.ussdCode.isEmpty()) {
            Log.e(TAG, "tache invalide ignoree");
            return false;
        }
        synchronized (VERROU) {
            if (appContext == null && context != null) appContext = context.getApplicationContext();
            purger();
            if (CONNUS.contains(job.retraitId)) {
                Log.d(TAG, "doublon ignore (en file ou en cours): " + job.retraitId);
                return false;
            }
            if (DEJA_VUS.containsKey(job.retraitId)) {
                Log.d(TAG, "doublon ignore (deja traite recemment): " + job.retraitId);
                return false;
            }
            CONNUS.add(job.retraitId);
            FILE.addLast(job);
            Log.d(TAG, "en file: " + job.retraitId + " (" + job.operator
                    + ") — " + FILE.size() + " en attente, en cours=" + idEnCours);
        }
        pompe();
        return true;
    }

    /** Nombre de retraits encore en attente (hors celui en cours). */
    public static int taille() {
        synchronized (VERROU) { return FILE.size(); }
    }

    /** Retrait actuellement traite, ou null. */
    public static String enCours() {
        synchronized (VERROU) { return idEnCours; }
    }

    /** Demarre la tache suivante si rien n'est en cours. */
    private static void pompe() {
        final Job job;
        synchronized (VERROU) {
            if (enCours) return;                 // un retrait occupe deja la SIM
            job = FILE.pollFirst();
            if (job == null) return;
            enCours   = true;
            idEnCours = job.retraitId;
        }
        H.post(() -> demarrer(job));
    }

    private static void demarrer(final Job job) {
        Log.d(TAG, "demarrage " + job.retraitId + " (" + job.operator + ")");

        // Un seul appel a terminer(), quelle que soit la voie (moteur ou garde-fou).
        final boolean[] clos = { false };

        final Runnable garde = () -> {
            boolean premier;
            synchronized (VERROU) { premier = !clos[0]; clos[0] = true; }
            if (!premier) return;
            Log.e(TAG, "delai de garde depasse pour " + job.retraitId);
            UssdAccessibilityService.disarm();
            remonter(job, false,
                "Aucune reponse du moteur USSD apres " + (WATCHDOG_MS / 1000) + " s. "
                + "Retrait NON confirme : verifiez le telephone avant toute relance.");
            terminer();
        };
        H.postDelayed(garde, WATCHDOG_MS);

        UssdEngine.UssdCallback interne = (id, success, response) -> {
            boolean premier;
            synchronized (VERROU) { premier = !clos[0]; clos[0] = true; }
            if (!premier) {
                Log.d(TAG, "resultat tardif ignore pour " + id);
                return;
            }
            H.removeCallbacks(garde);
            remonter(job, success, response);
            terminer();
        };

        try {
            if (!job.ussdPin.isEmpty()) {
                // Orange : PIN tape a l'invite, eventuellement sur plusieurs ecrans
                UssdEngine.sendUssdInteractive(appContext, job.retraitId, job.ussdCode,
                        job.operator, job.ussdPin, job.menuReply, job.maxSteps, interne);
            } else {
                // MVola / MVola KM : PIN deja concatene dans le code USSD
                UssdEngine.sendUssd(appContext, job.retraitId, job.ussdCode,
                        job.operator, interne);
            }
        } catch (Throwable t) {
            Log.e(TAG, "demarrage: " + t.getMessage());
            interne.onResult(job.retraitId, false, "Erreur interne: " + t.getMessage());
        }
    }

    private static void remonter(Job job, boolean success, String response) {
        try {
            if (job.callback != null) job.callback.onResult(job.retraitId, success, response);
        } catch (Throwable t) {
            Log.e(TAG, "callback: " + t.getMessage());
        }
    }

    /** Libere la SIM, puis enchaine apres la pause reglementaire. */
    private static void terminer() {
        final String fini;
        synchronized (VERROU) {
            fini      = idEnCours;
            enCours   = false;
            idEnCours = null;
        }
        Log.d(TAG, "termine " + fini + " — pause " + GAP_MS + " ms avant le suivant");
        synchronized (VERROU) {
            if (fini != null) {
                CONNUS.remove(fini);
                DEJA_VUS.put(fini, System.currentTimeMillis());   // refus pendant MEMOIRE_MS
            }
        }
        H.postDelayed(UssdQueue::pompe, GAP_MS);
    }

    /** Oublie les retraits traites il y a plus de MEMOIRE_MS (borne la memoire). */
    private static void purger() {
        long limite = System.currentTimeMillis() - MEMOIRE_MS;
        java.util.Iterator<java.util.Map.Entry<String, Long>> it = DEJA_VUS.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < limite) it.remove();
        }
    }
}
