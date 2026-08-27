package mg.smsgateway.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.RequiresApi;
import mg.smsgateway.network.ApiClient;
import mg.smsgateway.utils.Prefs;
import mg.smsgateway.utils.SimUtils;
import java.util.HashSet;
import java.util.Set;

/**
 * UssdBalanceScheduler — VERSION ULTRA PRO ROBOT
 *
 * FIX: setInexactRepeating → setExactAndAllowWhileIdle + reschedule.
 * Android 12+ ignore setInexactRepeating pour les apps critiques.
 * On utilise setExact + replanification dans onReceive pour fiabilité max.
 */
public class UssdBalanceScheduler extends BroadcastReceiver {

    private static final String TAG         = "UssdBalanceScheduler";
    private static final String ACTION_CHECK = "mg.smsgateway.ACTION_USSD_BALANCE_CHECK";
    private static final int    REQUEST_CODE = 9001;
    private static final long   DELAI_APRES_MOUVEMENT_MS = 20_000L;

    public static void apresMouvement(Context context, String operator) {
        if (context == null || operator == null || operator.isEmpty()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        final Context ctx = context.getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                Prefs p = new Prefs(ctx);
                if (!p.getUssdCheckEnabled()) return;
                Log.d(TAG, "solde " + operator + " : contrôle après mouvement");
                checkOperator(ctx, p, operator);
            } catch (Exception e) {
                Log.e(TAG, "apresMouvement: " + e.getMessage());
            }
        }, DELAI_APRES_MOUVEMENT_MS);
    }

    // -----------------------------------------------------------------------
    // FIX: setExactAndAllowWhileIdle au lieu de setInexactRepeating
    // -----------------------------------------------------------------------
    public static void start(Context context) {
        scheduleNext(context, 5_000L); // premier check dans 5s
        Log.d(TAG, "Scheduler démarré (setExact)");
    }

    public static void stop(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = buildPendingIntent(context);
        am.cancel(pi);
        Log.d(TAG, "Scheduler arrêté");
    }

    private static void scheduleNext(Context context, long delaiMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = buildPendingIntent(context);
        long trigger = SystemClock.elapsedRealtime() + delaiMs;
        // FIX: setExactAndAllowWhileIdle — garanti même si Doze mode actif
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        } else {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        }
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, UssdBalanceScheduler.class);
        intent.setAction(ACTION_CHECK);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }

    // -----------------------------------------------------------------------
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_CHECK.equals(intent.getAction())) return;

        Prefs prefs = new Prefs(context);
        if (!prefs.getUssdCheckEnabled()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        Log.d(TAG, "Exécution check solde automatique");

        // FIX: replanifie l'alarme suivante AVANT le check
        // (si le check prend du temps, l'alarme est déjà posée)
        long intervalMs = prefs.getUssdCheckIntervalMinutes() * 60 * 1000L;
        scheduleNext(context, intervalMs);

        // Check par opérateur actif uniquement
        Set<String> actifs = operateursActifs(context);
        boolean detectionOk = !actifs.isEmpty();

        if (!detectionOk) {
            Log.w(TAG, "SIM non détectée — check tous opérateurs (fallback)");
        }

        if (!detectionOk || actifs.contains(SimUtils.SIM_ORANGE))
            checkOperator(context, prefs, "orange");
        else Log.d(TAG, "orange ignoré : pas de SIM Orange");

        if (!detectionOk || actifs.contains(SimUtils.SIM_YAS))
            checkOperator(context, prefs, "mvola");
        else Log.d(TAG, "mvola ignoré : pas de SIM Telma/YAS");

        if (!detectionOk || actifs.contains(SimUtils.SIM_AIRTEL))
            checkOperator(context, prefs, "airtel");
        else Log.d(TAG, "airtel ignoré : pas de SIM Airtel");
    }

    private static Set<String> operateursActifs(Context context) {
        Set<String> set = new HashSet<>();
        try {
            org.json.JSONArray arr = SimUtils.getSimStatuses(context);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                if (o.optBoolean("active", false)) set.add(o.getString("name"));
            }
        } catch (Exception e) {
            Log.e(TAG, "operateursActifs: " + e.getMessage());
        }
        return set;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static void checkOperator(Context context, Prefs prefs, String operator) {
        String code = prefs.getUssdBalance(operator);
        if ("orange".equals(operator) && prefs.isOrangeMarchand()) {
            String m = prefs.getUssdBalanceMarchand();
            if (m != null && !m.trim().isEmpty()) code = m;
        }
        if (code == null || code.trim().isEmpty()) return;

        // FIX: ensureExecutor avant tout appel réseau
        ApiClient.ensureExecutor();

        UssdQueue.enqueueLectureSolde(context, operator, code,
            (id, success, response) -> {
                if (!success) {
                    Log.e(TAG, "Échec check solde " + operator + ": " + response);
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
                            Log.d(TAG, "Solde " + operator + " envoyé");
                        }
                        @Override public void onError(String e) {
                            Log.e(TAG, "Erreur envoi solde " + operator + ": " + e);
                        }
                    });
            });
    }
}
