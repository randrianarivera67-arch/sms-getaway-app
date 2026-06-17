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
    private void checkOperator(Context context, Prefs prefs, String operator) {
        String code = prefs.getUssdBalance(operator);
        if (code == null || code.trim().isEmpty()) return;

        UssdEngine.sendUssd(context, "balance_" + operator, code, operator,
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
