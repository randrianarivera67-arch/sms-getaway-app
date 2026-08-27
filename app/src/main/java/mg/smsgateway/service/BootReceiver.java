package mg.smsgateway.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import mg.smsgateway.utils.Prefs;

/**
 * BootReceiver — VERSION ULTRA PRO ROBOT
 *
 * Démarre le service après reboot ET repose toutes les alarmes.
 * Les AlarmManager et WakeLock sont perdus au reboot — il faut
 * les recréer ici.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        Log.d(TAG, "Boot détecté — démarrage du service");
        Prefs prefs = new Prefs(context);

        // 1. Démarre le GatewayService
        if (prefs.getAutoStart() && !prefs.getServerUrl().isEmpty()) {
            Intent serviceIntent = new Intent(context, GatewayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d(TAG, "GatewayService démarré après reboot");
        }

        // 2. Repose l'alarme de consultation de solde
        if (prefs.getUssdCheckEnabled()) {
            UssdBalanceScheduler.start(context);
            Log.d(TAG, "Alarme solde reposée après reboot");
        }
    }
}
