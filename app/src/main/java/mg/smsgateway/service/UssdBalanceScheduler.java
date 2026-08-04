package mg.smsgateway.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.annotation.RequiresApi;
import mg.smsgateway.network.ApiClient;
import mg.smsgateway.utils.Prefs;

/**
 * Planifie l'envoi périodique des codes USSD de vérification de solde
 * (Orange, MVola, Airtel) lorsque le toggle "Codes USSD Solde" est activé.
 */
public class UssdBalanceScheduler extends BroadcastReceiver {

    private static final String TAG = "UssdBalanceScheduler";
    private static final String ACTION_CHECK = "mg.smsgateway.ACTION_USSD_BALANCE_CHECK";
    private static final int    REQUEST_CODE = 9001;

    /**
     * Delai avant de relire le solde apres un mouvement. L'operateur doit avoir
     * fini d'enregistrer l'operation ; sinon on releve l'ancienne valeur et on
     * croit a tort que rien n'a bouge.
     */
    private static final long DELAI_APRES_MOUVEMENT_MS = 20_000L;

    /**
     * Declenche UNE consultation de solde apres un mouvement d'argent.
     *
     * <p>C'est le bon moment : le solde ne change QUE lorsqu'une transaction a
     * lieu. Interroger l'operateur toutes les deux minutes alors que rien ne
     * bouge use la cadence USSD pour rien — et cette cadence est justement ce
     * qui limite le nombre de retraits possibles.</p>
     *
     * <p>La consultation passe par la file : elle ne peut donc jamais tomber
     * au milieu d'un retrait.</p>
     */
    public static void apresMouvement(Context context, String operator) {
        if (context == null || operator == null || operator.isEmpty()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        final Context ctx = context.getApplicationContext();
        final String op = operator;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                Prefs p = new Prefs(ctx);
                if (!p.getUssdCheckEnabled()) return;
                Log.d(TAG, "solde " + op + " : controle apres mouvement");
                checkOperator(ctx, p, op);
            } catch (Exception e) {
                Log.e(TAG, "apresMouvement: " + e.getMessage());
            }
        }, DELAI_APRES_MOUVEMENT_MS);
    }

    public static void start(Context context) {
        Prefs prefs = new Prefs(context);
        long intervalMs = prefs.getUssdCheckIntervalMinutes() * 60 * 1000;

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, UssdBalanceScheduler.class);
        intent.setAction(ACTION_CHECK);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);

        am.cancel(pi);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 5000,
            intervalMs, pi);

        Log.d(TAG, "Scheduler démarré — interval: " + prefs.getUssdCheckIntervalMinutes() + " min");
    }

    public static void stop(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, UssdBalanceScheduler.class);
        intent.setAction(ACTION_CHECK);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
        am.cancel(pi);
        Log.d(TAG, "Scheduler arrêté");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_CHECK.equals(intent.getAction())) return;

        Prefs prefs = new Prefs(context);
        if (!prefs.getUssdCheckEnabled()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        Log.d(TAG, "Exécution check solde automatique");
        checkOperator(context, prefs, "orange");
        checkOperator(context, prefs, "mvola");
        checkOperator(context, prefs, "airtel");
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static void checkOperator(Context context, Prefs prefs, String operator) {
        String code = prefs.getUssdBalance(operator);
        if (code == null || code.trim().isEmpty()) return;

        // Deux raisons de passer par la file :
        //  - une session USSD est unique par SIM : une consultation lancee
        //    pendant un retrait detruirait ce retrait ;
        //  - la file impose deja une pause entre deux compositions, ce qui
        //    menage la cadence acceptee par l'operateur.
        UssdQueue.enqueueLectureSolde(context, operator, code,
            (id, success, response) -> {
                if (!success) {
                    Log.e(TAG, "Echec check solde " + operator + ": " + response);
                    return;
                }
                long now = System.currentTimeMillis();
                prefs.setLastUssdCheckTime(operator, now);

                String serverUrl = prefs.getServerUrl();
                String apiKey    = prefs.getApiKey();
                if (serverUrl.isEmpty()) return;

                ApiClient.sendSoldeCheck(serverUrl, apiKey, operator, response, now,
                    new ApiClient.Callback() {
                        @Override public void onSuccess(String r) {
                            Log.d(TAG, "Solde " + operator + " envoyé: " + response);
                        }
                        @Override public void onError(String e) {
                            Log.e(TAG, "Erreur envoi solde " + operator + ": " + e);
                        }
                    });
            });
    }
}
