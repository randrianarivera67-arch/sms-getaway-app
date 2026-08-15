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
 *   <li><b>Pause</b> de {@link #gapMs} entre deux retraits (demande metier :
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

    /**
     * Pause entre deux retraits (ms).
     *
     * <p><b>Prudence deliberee.</b> Les limites de cadence appliquees par les
     * operateurs malgaches (Orange, Telma) ne sont pas publiees : on ne sait pas
     * a partir de quel rythme une nouvelle session USSD est refusee, ni si un
     * envoi trop rapide peut etre rejete APRES avoir debite. On part donc d'une
     * valeur large plutot que du minimum demande, et le serveur peut l'ajuster
     * sans nouvelle version de l'application (cle Settings <code>ussd_gap_ms</code>,
     * transmise dans la commande).</p>
     */
    private static final long GAP_DEFAUT_MS = 3000L;
    /** Bornes de securite : meme mal configure, on reste dans le raisonnable. */
    private static final long GAP_MIN_MS = 1000L;
    private static final long GAP_MAX_MS = 60_000L;

    private static volatile long gapMs = GAP_DEFAUT_MS;

    /**
     * Echecs consecutifs. Un operateur qui refuse peut etre en train de limiter
     * la cadence : on ralentit progressivement au lieu d'insister au meme rythme.
     */
    private static volatile int echecsConsecutifs = 0;
    private static final int  MAX_ECHECS_AVANT_PAUSE_LONGUE = 3;
    private static final long PAUSE_LONGUE_MS = 60_000L;

    /** Regle la pause depuis le serveur (valeur bornee). */
    public static void setGapMs(long ms) {
        if (ms <= 0) return;
        long v = Math.max(GAP_MIN_MS, Math.min(GAP_MAX_MS, ms));
        if (v != gapMs) {
            gapMs = v;
            Log.d(TAG, "pause entre retraits reglee a " + v + " ms");
        }
    }

    /** Pause a appliquer maintenant, allongee si l'operateur enchaine les refus. */
    private static long pauseCourante() {
        if (echecsConsecutifs >= MAX_ECHECS_AVANT_PAUSE_LONGUE) {
            Log.e(TAG, echecsConsecutifs + " echecs consecutifs -> pause longue de "
                    + (PAUSE_LONGUE_MS / 1000) + " s");
            return PAUSE_LONGUE_MS;
        }
        return gapMs;
    }

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
        /** Pause souhaitee par le serveur (0 = garder la valeur courante). */
        public final long gapMs;
        /** true = simple consultation de solde : lecture d'ecran, aucune saisie. */
        public final boolean lectureSolde;
        public final UssdEngine.UssdCallback callback;

        public Job(String retraitId, String ussdCode, String operator, String ussdPin,
                   String menuReply, int maxSteps, UssdEngine.UssdCallback callback) {
            this(retraitId, ussdCode, operator, ussdPin, menuReply, maxSteps, 0L, callback);
        }

        public Job(String retraitId, String ussdCode, String operator, String ussdPin,
                   String menuReply, int maxSteps, long gapMs,
                   UssdEngine.UssdCallback callback) {
            this(retraitId, ussdCode, operator, ussdPin, menuReply, maxSteps, gapMs, false, callback);
        }

        public Job(String retraitId, String ussdCode, String operator, String ussdPin,
                   String menuReply, int maxSteps, long gapMs, boolean lectureSolde,
                   UssdEngine.UssdCallback callback) {
            this.gapMs = gapMs;
            this.lectureSolde = lectureSolde;
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
            if (job.gapMs > 0) setGapMs(job.gapMs);
            CONNUS.add(job.retraitId);
            FILE.addLast(job);
            Log.d(TAG, "en file: " + job.retraitId + " (" + job.operator
                    + ") — " + FILE.size() + " en attente, en cours=" + idEnCours);
        }
        pompe();
        return true;
    }

    /**
     * Met une consultation de solde en file.
     *
     * <p>Elle emprunte la MEME file que les retraits : une session USSD est
     * unique par SIM, et une consultation lancee pendant un retrait
     * detruirait ce dernier. C'est precisement pour cela que la consultation
     * ne doit jamais composer de son cote.</p>
     */
    public static boolean enqueueLectureSolde(Context context, String operator,
                                              String ussdCode,
                                              UssdEngine.UssdCallback callback) {
        // Reference distincte des retraits, et renouvelee a chaque fois pour
        // ne pas etre bloquee par l'anti-doublon.
        String ref = "solde_" + operator + "_" + System.currentTimeMillis();
        return enqueue(context, new Job(ref, ussdCode, operator, "", "", 1, 0L, true, callback));
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
            if (job.lectureSolde) {
                // Consultation de solde : on lit l'ecran, on ne saisit rien.
                UssdEngine.lireSoldeUssd(appContext, job.retraitId, job.ussdCode,
                        job.operator, interne);
            } else if (!job.ussdPin.isEmpty() || !job.menuReply.isEmpty()) {
                // Orange : PIN tape a l'invite, eventuellement sur plusieurs ecrans.
                // Airtel multi-etape : pas de PIN separe, mais une sequence de menu
                // a saisir ecran par ecran — il faut aussi le mode interactif,
                // sinon le code est compose et plus rien n'est tape.
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
        // Suivi de cadence : un enchainement d'echecs peut signaler que
        // l'operateur limite les sessions. On ralentit alors la file.
        if (success) echecsConsecutifs = 0;
        else if (echecsConsecutifs < 100) echecsConsecutifs++;
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
        Log.d(TAG, "termine " + fini);
        synchronized (VERROU) {
            if (fini != null) {
                CONNUS.remove(fini);
                DEJA_VUS.put(fini, System.currentTimeMillis());   // refus pendant MEMOIRE_MS
            }
        }
        long pause = pauseCourante();
        Log.d(TAG, "pause de " + pause + " ms avant le retrait suivant");
        H.postDelayed(UssdQueue::pompe, pause);
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
