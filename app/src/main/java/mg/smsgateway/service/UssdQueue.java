package mg.smsgateway.service;

import android.content.Context;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * UssdQueue — VERSION ULTRA PRO ROBOT
 *
 * FIX KRITIKA: Thread.sleep(1200L) supprimé du Main Thread.
 * Remplacé par Handler.postDelayed() — jamais de blocage UI.
 *
 * Architecture:
 *  - File FIFO séquentielle (un seul USSD à la fois)
 *  - ReveilActivity → Handler.postDelayed(1200ms) → composition USSD
 *  - Watchdog 285s si le moteur ne répond pas
 *  - Anti-doublon avec mémoire 30 min
 *  - Backoff exponentiel sur échecs consécutifs
 */
public final class UssdQueue {

    private static final String TAG = "UssdQueue";

    private static final long GAP_DEFAUT_MS = 3000L;
    private static final long GAP_MIN_MS    = 1000L;
    private static final long GAP_MAX_MS    = 60_000L;

    private static volatile long gapMs = GAP_DEFAUT_MS;

    private static volatile int echecsConsecutifs = 0;
    private static final int  MAX_ECHECS_AVANT_PAUSE_LONGUE = 3;
    private static final long PAUSE_LONGUE_MS = 60_000L;

    // FIX KRITIKA: doit être > délai interne moteur (240s max absolu)
    // Filet de DERNIER RECOURS : ne se declenche que si le moteur ne rappelle
    // jamais. Doit rester au-dessus du plus long delai interne :
    //   moteur retrait 90 s absolu, service arme 120 s plafond.
    // ATTENTION : toute baisse des delais moteur doit etre repercutee ici.
    private static final long WATCHDOG_MS = 150_000L;

    public static void setGapMs(long ms) {
        if (ms <= 0) return;
        long v = Math.max(GAP_MIN_MS, Math.min(GAP_MAX_MS, ms));
        if (v != gapMs) {
            gapMs = v;
            Log.d(TAG, "pause réglée à " + v + " ms");
        }
    }

    private static long pauseCourante() {
        if (echecsConsecutifs >= MAX_ECHECS_AVANT_PAUSE_LONGUE) {
            Log.e(TAG, echecsConsecutifs + " échecs → pause longue "
                + (PAUSE_LONGUE_MS / 1000) + "s");
            return PAUSE_LONGUE_MS;
        }
        return gapMs;
    }

    // -----------------------------------------------------------------------
    public static final class Job {
        public final String  retraitId;
        public final String  ussdCode;
        public final String  operator;
        public final String  ussdPin;
        public final String  menuReply;
        public final int     maxSteps;
        public final long    gapMs;
        public final boolean lectureSolde;
        public final UssdEngine.UssdCallback callback;

        public Job(String retraitId, String ussdCode, String operator,
                   String ussdPin, String menuReply, int maxSteps,
                   UssdEngine.UssdCallback callback) {
            this(retraitId, ussdCode, operator, ussdPin, menuReply, maxSteps,
                0L, callback);
        }

        public Job(String retraitId, String ussdCode, String operator,
                   String ussdPin, String menuReply, int maxSteps,
                   long gapMs, UssdEngine.UssdCallback callback) {
            this(retraitId, ussdCode, operator, ussdPin, menuReply, maxSteps,
                gapMs, false, callback);
        }

        public Job(String retraitId, String ussdCode, String operator,
                   String ussdPin, String menuReply, int maxSteps,
                   long gapMs, boolean lectureSolde,
                   UssdEngine.UssdCallback callback) {
            this.gapMs        = gapMs;
            this.lectureSolde = lectureSolde;
            this.retraitId    = retraitId;
            this.ussdCode     = ussdCode;
            this.operator     = operator;
            this.ussdPin      = ussdPin   == null ? "" : ussdPin.trim();
            this.menuReply    = menuReply == null ? "" : menuReply.trim();
            this.maxSteps     = maxSteps < 1 ? 1 : maxSteps;
            this.callback     = callback;
        }
    }

    private static final long MEMOIRE_MS = 30 * 60 * 1000L;

    /** Retraits clients — servis en priorite absolue. */
    private static final Deque<Job>  FILE        = new ArrayDeque<>();
    /** Consultations de solde — servies seulement quand FILE est vide. */
    private static final Deque<Job>  FILE_SOLDE  = new ArrayDeque<>();
    private static final java.util.Map<String, Long> DEJA_VUS =
        new java.util.HashMap<>();
    private static final Set<String> CONNUS  = new HashSet<>();
    private static final Object      VERROU  = new Object();

    private static boolean enCours    = false;
    private static String  idEnCours  = null;
    private static Context appContext = null;

    private static final android.os.Handler H =
        new android.os.Handler(android.os.Looper.getMainLooper());

    private UssdQueue() {}

    // -----------------------------------------------------------------------
    public static boolean enqueue(Context context, Job job) {
        if (job == null || job.retraitId == null || job.retraitId.isEmpty()
                || job.ussdCode == null || job.ussdCode.isEmpty()) {
            Log.e(TAG, "tâche invalide ignorée");
            return false;
        }
        synchronized (VERROU) {
            if (appContext == null && context != null) {
                appContext = context.getApplicationContext();
            }
            purger();
            if (CONNUS.contains(job.retraitId)) {
                Log.d(TAG, "doublon en file: " + job.retraitId);
                return false;
            }
            if (DEJA_VUS.containsKey(job.retraitId)) {
                Log.d(TAG, "doublon traité: " + job.retraitId);
                return false;
            }
            if (job.gapMs > 0) setGapMs(job.gapMs);
            CONNUS.add(job.retraitId);
            if (job.lectureSolde) {
                // ANTI-ACCUMULATION : le planificateur ajoute un solde a chaque
                // reveil et chaque reference est unique (horodatage) — l'anti-
                // doublon ne les filtre donc pas. Si les retraits s'enchainent,
                // les soldes s'empileraient sans fin puis se deverseraient tous
                // perimes. Un seul solde par operateur : le plus recent gagne.
                java.util.Iterator<Job> itS = FILE_SOLDE.iterator();
                while (itS.hasNext()) {
                    Job vieux = itS.next();
                    if (vieux.operator != null
                            && vieux.operator.equals(job.operator)) {
                        itS.remove();
                        CONNUS.remove(vieux.retraitId);
                        Log.d(TAG, "solde " + job.operator + " perime remplace");
                    }
                }
                FILE_SOLDE.addLast(job);
                Log.d(TAG, "en file SOLDE: " + job.retraitId + " (" + job.operator
                    + ") — " + FILE_SOLDE.size() + " solde(s), "
                    + FILE.size() + " retrait(s), en cours=" + idEnCours);
            } else {
                FILE.addLast(job);
                Log.d(TAG, "en file RETRAIT: " + job.retraitId + " (" + job.operator
                    + ") — " + FILE.size() + " retrait(s), en cours=" + idEnCours);
            }
        }
        pompe();
        return true;
    }

    public static boolean enqueueLectureSolde(Context context, String operator,
                                              String ussdCode,
                                              UssdEngine.UssdCallback callback) {
        String ref = "solde_" + operator + "_" + System.currentTimeMillis();
        return enqueue(context, new Job(ref, ussdCode, operator, "", "",
            1, 0L, true, callback));
    }

    /** Nombre total de taches en attente (retraits + soldes). */
    public static int    taille()   {
        synchronized (VERROU) { return FILE.size() + FILE_SOLDE.size(); }
    }
    /** Nombre de retraits clients en attente. */
    public static int    tailleRetraits() {
        synchronized (VERROU) { return FILE.size(); }
    }
    public static String enCours()  {
        synchronized (VERROU) { return idEnCours; }
    }

    private static void pompe() {
        final Job job;
        synchronized (VERROU) {
            if (enCours) return;
            // PRIORITE : un retrait client passe toujours en premier.
            Job suivant = FILE.pollFirst();
            if (suivant == null) {
                suivant = FILE_SOLDE.pollFirst();
                if (suivant != null) {
                    Log.d(TAG, "aucun retrait en attente -> solde " + suivant.retraitId);
                }
            } else if (!FILE_SOLDE.isEmpty()) {
                Log.d(TAG, FILE_SOLDE.size()
                    + " solde(s) mis en attente : un retrait passe d'abord");
            }
            if (suivant == null) return;
            job       = suivant;
            enCours   = true;
            idEnCours = job.retraitId;
        }
        H.post(() -> demarrer(job));
    }

    // -----------------------------------------------------------------------
    // FIX KRITIKA: Thread.sleep supprimé → Handler.postDelayed
    // -----------------------------------------------------------------------
    private static void demarrer(final Job job) {
        Log.d(TAG, "démarrage " + job.retraitId + " (" + job.operator + ")");

        // FIX ROBOT : on ne compose JAMAIS sur une ligne sale. preparerLigne()
        // leve l'ecran de verrouillage PUIS ferme les boites USSD restees
        // ouvertes. Sans cela la nouvelle boite s'ouvrait derriere l'ancienne :
        // ni saisie, ni validee, operation perdue.
        // preparerLigne rappelle TOUJOURS, meme en cas d'echec. Aucun
        // Thread.sleep : tout passe par le Handler du thread principal.
        UssdAccessibilityService.preparerLigne(appContext, new Runnable() {
            @Override public void run() { demarrerComposition(job); }
        });
    }

    private static void demarrerComposition(final Job job) {
        final boolean[] clos = {false};

        final Runnable garde = () -> {
            boolean premier;
            synchronized (VERROU) { premier = !clos[0]; clos[0] = true; }
            if (!premier) return;
            Log.e(TAG, "watchdog déclenché pour " + job.retraitId);
            UssdAccessibilityService.disarm();
            remonter(job, false,
                "Aucune réponse du moteur USSD après " + (WATCHDOG_MS / 1000)
                + "s. Retrait NON confirmé.");
            terminer();
        };
        H.postDelayed(garde, WATCHDOG_MS);

        UssdEngine.UssdCallback interne = (id, success, response) -> {
            boolean premier;
            synchronized (VERROU) { premier = !clos[0]; clos[0] = true; }
            if (!premier) {
                Log.d(TAG, "résultat tardif ignoré pour " + id);
                return;
            }
            H.removeCallbacks(garde);
            remonter(job, success, response);
            terminer();
        };

        try {
            if (job.lectureSolde) {
                UssdEngine.lireSoldeUssd(appContext, job.retraitId, job.ussdCode,
                    job.operator, interne);
            } else if (!job.ussdPin.isEmpty() || !job.menuReply.isEmpty()) {
                UssdEngine.sendUssdInteractive(appContext, job.retraitId, job.ussdCode,
                    job.operator, job.ussdPin, job.menuReply, job.maxSteps, interne);
            } else {
                UssdEngine.sendUssd(appContext, job.retraitId, job.ussdCode,
                    job.operator, interne);
            }
        } catch (Throwable t) {
            Log.e(TAG, "demarrage: " + t.getMessage());
            interne.onResult(job.retraitId, false, "Erreur: " + t.getMessage());
        }
    }

    private static void remonter(Job job, boolean success, String response) {
        if (success) echecsConsecutifs = 0;
        else if (echecsConsecutifs < 100) echecsConsecutifs++;
        try {
            if (job.callback != null) job.callback.onResult(job.retraitId, success, response);
        } catch (Throwable t) { Log.e(TAG, "callback: " + t.getMessage()); }
    }

    private static void terminer() {
        final String fini;
        synchronized (VERROU) {
            fini      = idEnCours;
            enCours   = false;
            idEnCours = null;
        }
        Log.d(TAG, "terminé " + fini);
        synchronized (VERROU) {
            if (fini != null) {
                CONNUS.remove(fini);
                DEJA_VUS.put(fini, System.currentTimeMillis());
            }
        }
        long pause = pauseCourante();
        Log.d(TAG, "pause " + pause + " ms avant suivant");
        H.postDelayed(UssdQueue::pompe, pause);
    }

    private static void purger() {
        long limite = System.currentTimeMillis() - MEMOIRE_MS;
        java.util.Iterator<java.util.Map.Entry<String, Long>> it =
            DEJA_VUS.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < limite) it.remove();
        }
    }
}
