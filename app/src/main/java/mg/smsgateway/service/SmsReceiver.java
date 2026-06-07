package mg.smsgateway.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import mg.smsgateway.R;
import mg.smsgateway.network.ApiClient;
import mg.smsgateway.ui.InboxActivity;
import mg.smsgateway.ui.MainActivity;
import mg.smsgateway.utils.Prefs;
import mg.smsgateway.utils.SimUtils;
import mg.smsgateway.utils.SmsQueue;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG              = "SmsReceiver";
    public  static final String SMS_RECEIVED_ACTION = "mg.smsgateway.SMS_RECEIVED";
    private static final String CHANNEL_ID       = "sms_notif_channel";
    private static final int    NOTIF_BASE_ID    = 2000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        try {
            Object[] pdus = (Object[]) bundle.get("pdus");
            String format = bundle.getString("format");

            // Récupération du slot SIM (Android 5.1+)
            int simSlot = bundle.getInt("android.telephony.extra.SLOT_INDEX", -1);
            if (simSlot < 0) simSlot = bundle.getInt("slot", -1);
            if (simSlot < 0) simSlot = bundle.getInt("simId", -1);
            if (simSlot < 0) simSlot = bundle.getInt("subscription", -1);

            if (pdus == null || pdus.length == 0) return;

            StringBuilder fullMessage = new StringBuilder();
            String sender = "";

            for (Object pdu : pdus) {
                SmsMessage smsMsg = (format != null)
                    ? SmsMessage.createFromPdu((byte[]) pdu, format)
                    : SmsMessage.createFromPdu((byte[]) pdu);
                if (smsMsg != null) {
                    sender = smsMsg.getOriginatingAddress();
                    fullMessage.append(smsMsg.getMessageBody());
                }
            }

            // Résolution SIM : slot détecté ou devinée depuis le numéro
            if (simSlot < 0 && sender != null) {
                simSlot = SimUtils.guessSlotFromNumber(sender);
            }
            if (simSlot < 0) simSlot = 0; // fallback SIM 1

            // Clamp à 0-2
            if (simSlot > 2) simSlot = 2;

            String simName = SimUtils.getSimName(simSlot);
            String message = fullMessage.toString();

            Log.d(TAG, "SMS reçu de " + sender + " via " + simName + " (slot " + simSlot + ")");

            mg.smsgateway.model.SmsMessage appSms =
                    new mg.smsgateway.model.SmsMessage(sender, message, simName, simSlot);

            Prefs prefs = new Prefs(context);
            prefs.incrementSmsReceived();
            prefs.incrementSimCount(simSlot);
            prefs.incrementNotifCount();

            // MAJ stats pending
            SmsQueue queue = SmsQueue.getInstance(context);
            prefs.setSmsPending(queue.getPendingCount());

            // Notification utilisateur visible et accessible
            showSmsNotification(context, prefs, sender, message, simName, simSlot);

            // Broadcast vers UI
            Intent uiIntent = new Intent(SMS_RECEIVED_ACTION);
            uiIntent.putExtra("from", sender);
            uiIntent.putExtra("message", message);
            uiIntent.putExtra("sim", simName);
            uiIntent.putExtra("simSlot", simSlot);
            context.sendBroadcast(uiIntent);

            // Envoi vers serveur
            String serverUrl = prefs.getServerUrl();
            String apiKey    = prefs.getApiKey();

            if (!serverUrl.isEmpty()) {
                ApiClient.sendSms(serverUrl, apiKey, appSms, new ApiClient.Callback() {
                    @Override
                    public void onSuccess(String id) {
                        Log.d(TAG, "SMS transmis au serveur: " + id);
                        prefs.incrementSmsSent();
                        prefs.setSmsPending(queue.getPendingCount());
                        Intent doneIntent = new Intent("mg.smsgateway.SMS_SENT");
                        doneIntent.putExtra("simSlot", simSlot);
                        context.sendBroadcast(doneIntent);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Échec envoi serveur: " + error);
                        prefs.incrementSmsFailed();
                        queue.addToQueue(appSms);
                        prefs.setSmsPending(queue.getPendingCount());
                        Intent failIntent = new Intent("mg.smsgateway.SMS_FAILED");
                        failIntent.putExtra("error", error);
                        context.sendBroadcast(failIntent);
                    }
                });
            } else {
                queue.addToQueue(appSms);
                prefs.setSmsPending(queue.getPendingCount());
            }

        } catch (Exception e) {
            Log.e(TAG, "onReceive error: " + e.getMessage());
        }
    }

    private void showSmsNotification(Context ctx, Prefs prefs,
                                     String sender, String message,
                                     String simName, int simSlot) {
        try {
            NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            // Créer canal (Android 8+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "SMS Entrants", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Notifications des SMS reçus");
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }

            // Intent vers InboxActivity
            Intent tapIntent = new Intent(ctx, InboxActivity.class);
            tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(ctx,
                (int) System.currentTimeMillis(), tapIntent, piFlags);

            int notifId = NOTIF_BASE_ID + simSlot;
            int totalUnread = prefs.getNotifCount();
            String title = simName + " — " + (sender != null ? sender : "Inconnu");

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sms)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setNumber(totalUnread)
                .setColor(android.graphics.Color.parseColor(SimUtils.getSimColor(simSlot)));

            nm.notify(notifId, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "showNotification error: " + e.getMessage());
        }
    }
}
