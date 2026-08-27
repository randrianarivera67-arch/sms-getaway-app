package mg.smsgateway.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import mg.smsgateway.utils.Prefs;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Log.d(TAG, "Boot détecté — démarrage du service");
            Prefs prefs = new Prefs(context);

            if (prefs.getAutoStart() && !prefs.getServerUrl().isEmpty()) {
                Intent serviceIntent = new Intent(context, GatewayService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }

            if (prefs.getUssdCheckEnabled()) {
                UssdBalanceScheduler.start(context);
            }
        }
    }
}
