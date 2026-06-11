package mg.smsgateway.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import mg.smsgateway.R;
import mg.smsgateway.network.ApiClient;
import mg.smsgateway.ui.InboxActivity;
import mg.smsgateway.utils.Prefs;
import mg.smsgateway.utils.SimUtils;
import mg.smsgateway.utils.SmsQueue;
import java.util.ArrayList;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG                 = "SmsReceiver";
    public  static final String SMS_RECEIVED_ACTION = "mg.smsgateway.SMS_RECEIVED";
    public  static final String ACTION_REPLY        = "mg.smsgateway.ACTION_REPLY";
    public  static final String KEY_REPLY_TEXT      = "key_reply_text";
    public  static final String EXTRA_REPLY_TO      = "reply_to";
    public  static final String EXTRA_SUB_ID        = "sub_id";
    private static final String CHANNEL_ID          = "sms_notif_channel";
    private static final int    NOTIF_BASE_ID       = 2000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REPLY.equals(intent.getAction())) {
            handleReply(context, intent);
            return;
        }
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        try {
            Object[] pdus   = (Object[]) bundle.get("pdus");
            String   format = bundle.getString("format");

            // Subscription ID — fomba marina Android 5.1+
            int subId = bundle.getInt("subscription", -1);
            if (subId < 0) subId = bundle.getInt("android.telephony.extra.SUBSCRIPTION_INDEX", -1);

            // Slot fallback
            int simSlot = bundle.getInt("android.telephony.extra.SLOT_INDEX", -1);
            if (simSlot < 0) simSlot = bundle.getInt("slot", -1);
            if (simSlot < 0) simSlot = bundle.getInt("simId", -1);

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

            String message = fullMessage.toString();

            // Détection opérateur — ordre de priorité:
            // 1. SubscriptionManager (anarana SIM tena izy)
            // 2. Sender name (ORANGE, MVOLA, AIRTEL)
            // 3. Numéro préfixe (032, 034, 033)
            String simName;
            int detectedSlot;

            // 1. SubscriptionManager
            SimUtils.initSubscriptions(context);
            String subOperator = subId >= 0 ? SimUtils.getOperatorFromSubId(subId) : "Inconnu";

            if (!subOperator.equals("Inconnu")) {
                simName = subOperator;
                detectedSlot = SimUtils.getSlotFromOperatorName(simName);
            } else {
                // 2 & 3. Sender name + préfixe
                detectedSlot = SimUtils.guessSlotFromNumber(sender);
                if (detectedSlot >= 0) {
                    simName = SimUtils.getSimName(detectedSlot);
                } else if (simSlot >= 0) {
                    simName = SimUtils.getSimName(simSlot);
                    detectedSlot = simSlot;
                } else {
                    simName = "Inconnu";
                    detectedSlot = 0;
                }
            }

            final int finalSlot = detectedSlot;
            final String finalSim = simName;
            final int finalSubId = subId;

            Log.d(TAG, "SMS reçu de " + sender + " via " + simName
                + " (slot=" + detectedSlot + ", subId=" + subId + ")");

            mg.smsgateway.model.SmsMessage appSms =
                new mg.smsgateway.model.SmsMessage(sender, message, simName, detectedSlot);

            Prefs    prefs = new Prefs(context);
            SmsQueue queue = SmsQueue.getInstance(context);
            prefs.incrementSmsReceived();
            prefs.incrementSimCount(detectedSlot >= 0 ? detectedSlot : 0);
            prefs.incrementNotifCount();
            prefs.setSmsPending(queue.getPendingCount());

            showSmsNotification(context, prefs, sender, message,
                simName, detectedSlot, subId);

            Intent uiIntent = new Intent(SMS_RECEIVED_ACTION);
            uiIntent.putExtra("from",    sender);
            uiIntent.putExtra("message", message);
            uiIntent.putExtra("sim",     simName);
            uiIntent.putExtra("simSlot", detectedSlot);
            uiIntent.putExtra("subId",   subId);
            context.sendBroadcast(uiIntent);

            String serverUrl = prefs.getServerUrl();
            String apiKey    = prefs.getApiKey();

            // Voatahiry ao SQLite hatrany — na misy server na tsia
            queue.saveReceived(appSms, "pending");

            if (!serverUrl.isEmpty()) {
                ApiClient.sendSms(serverUrl, apiKey, appSms, new ApiClient.Callback() {
                    @Override
                    public void onSuccess(String id) {
                        // incrementSmsSent ao amin'ny GatewayService queueRetry ihany
                        // aza atao indroa eto
                        prefs.setSmsPending(queue.getPendingCount());
                        Intent i = new Intent("mg.smsgateway.SMS_SENT");
                        i.putExtra("simSlot", finalSlot);
                        context.sendBroadcast(i);
                    }
                    @Override
                    public void onError(String error) {
                        prefs.incrementSmsFailed();
                        queue.addToQueue(appSms);
                        prefs.setSmsPending(queue.getPendingCount());
                        Intent i = new Intent("mg.smsgateway.SMS_FAILED");
                        i.putExtra("error", error);
                        context.sendBroadcast(i);
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

    /**
     * Vérifie si le SMS vient d'un opérateur (pas d'un client)
     */
    private boolean isFromOperator(String sender) {
        if (sender == null) return false;
        String upper = sender.toUpperCase().trim();
        // Noms opérateurs connus
        if (upper.contains("ORANGE") || upper.contains("MVOLA") ||
            upper.contains("TELMA") || upper.contains("YAS") ||
            upper.contains("AIRTEL")) return true;
        // Tsy numero (tsy manomboka amin'ny 0 na +)
        String digits = sender.replaceAll("[^0-9]", "");
        return digits.length() < 6; // operator names fohy
    }

    private void handleReply(Context context, Intent intent) {
        try {
            Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
            if (remoteInput == null) return;
            CharSequence replyText = remoteInput.getCharSequence(KEY_REPLY_TEXT);
            String replyTo = intent.getStringExtra(EXTRA_REPLY_TO);
            int subId = intent.getIntExtra(EXTRA_SUB_ID, -1);
            if (replyText == null || replyTo == null) return;

            // Mampiasa SIM marina rehefa mamaly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && subId >= 0) {
                try {
                    SmsManager sm = SmsManager.getSmsManagerForSubscriptionId(subId);
                    ArrayList<String> parts = sm.divideMessage(replyText.toString());
                    sm.sendMultipartTextMessage(replyTo, null, parts, null, null);
                    Log.d(TAG, "Reply sent via subId=" + subId);
                } catch (Exception e) {
                    // Fallback default
                    sendReplyDefault(replyTo, replyText.toString());
                }
            } else {
                sendReplyDefault(replyTo, replyText.toString());
            }

            int notifId = intent.getIntExtra("notif_id", NOTIF_BASE_ID);
            NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notifId);

        } catch (Exception e) {
            Log.e(TAG, "handleReply error: " + e.getMessage());
        }
    }

    private void sendReplyDefault(String to, String message) {
        try {
            SmsManager sm = SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(message);
            sm.sendMultipartTextMessage(to, null, parts, null, null);
        } catch (Exception e) {
            Log.e(TAG, "sendReplyDefault error: " + e.getMessage());
        }
    }

    private int getOperatorIcon(String simName) {
        if (simName == null) return R.drawable.ic_sms;
        if (simName.contains("Orange")) return R.drawable.ic_operator_orange;
        if (simName.contains("YAS") || simName.contains("Telma") || simName.contains("MVola"))
            return R.drawable.ic_operator_yas;
        if (simName.contains("Airtel")) return R.drawable.ic_operator_airtel;
        return R.drawable.ic_sms;
    }

    private void showSmsNotification(Context ctx, Prefs prefs,
                                     String sender, String message,
                                     String simName, int simSlot, int subId) {
        try {
            NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "SMS Entrants", NotificationManager.IMPORTANCE_HIGH);
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }

            int notifId = NOTIF_BASE_ID + (simSlot >= 0 ? simSlot : 0);
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;

            Intent tapIntent = new Intent(ctx, InboxActivity.class);
            tapIntent.putExtra(InboxActivity.EXTRA_FILTER, "all");
            tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent tapPi = PendingIntent.getActivity(ctx, notifId, tapIntent, piFlags);

            // Reply action — mampiditra subId mba hampiasa SIM marina
            RemoteInput remoteInput = new RemoteInput.Builder(KEY_REPLY_TEXT)
                .setLabel("Répondre à " + sender).build();
            Intent replyIntent = new Intent(ctx, SmsReceiver.class);
            replyIntent.setAction(ACTION_REPLY);
            replyIntent.putExtra(EXTRA_REPLY_TO, sender);
            replyIntent.putExtra(EXTRA_SUB_ID, subId);
            replyIntent.putExtra("notif_id", notifId);
            PendingIntent replyPi = PendingIntent.getBroadcast(ctx,
                notifId + 1000, replyIntent, piFlags);

            NotificationCompat.Action replyAction =
                new NotificationCompat.Action.Builder(
                    getOperatorIcon(simName), "Répondre", replyPi)
                .addRemoteInput(remoteInput).build();

            String opColor = SimUtils.getColorFromOperator(simName);
            String title = simName + "  •  " + (sender != null ? sender : "Inconnu");

            NotificationCompat.Builder builder =
                new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_sms)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(message).setSummaryText(simName))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(tapPi)
                    .setNumber(prefs.getNotifCount())
                    .setColor(android.graphics.Color.parseColor(opColor))
                    .addAction(replyAction);

            nm.notify(notifId, builder.build());

        } catch (Exception e) {
            Log.e(TAG, "showNotification error: " + e.getMessage());
        }
    }
}
