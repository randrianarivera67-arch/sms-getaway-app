package mg.smsgateway.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import mg.smsgateway.R;
import mg.smsgateway.network.ApiClient;
import mg.smsgateway.ui.MainActivity;
import mg.smsgateway.utils.Prefs;
import mg.smsgateway.utils.SmsQueue;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class GatewayService extends Service {

    private static final String TAG               = "GatewayService";
    private static final String CHANNEL_ID        = "sms_gateway_channel";
    private static final int    NOTIFICATION_ID   = 1001;
    private static final long   HEARTBEAT_INTERVAL = 30_000L;
    private static final long   QUEUE_RETRY_INTERVAL = 60_000L;

    public static final AtomicBoolean running = new AtomicBoolean(false);

    private int heartbeatCount = 0;
    private static final java.util.Map<String,String> BALANCE_USSD = new java.util.HashMap<String,String>() {{
        put("orange", "#144*5*3*2026#");
        put("mvola",  "#111*1*6*1*2011#");
        put("airtel", "*123#");
    }};
    private final java.util.Set<String> processingRetraits = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private Handler handler;
    private Prefs prefs;
    private PowerManager.WakeLock wakeLock;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        prefs   = new Prefs(this);
        createNotificationChannel();
        startNetworkMonitor();

        // WakeLock pour rester actif
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SMSGateway:WakeLock");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (isRunning.compareAndSet(false, true)) {
            startForeground(NOTIFICATION_ID, buildNotification(
                "Service actif — en attente de SMS..."));
            running.set(true);

            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(12 * 60 * 60 * 1000L); // max 12h
            }

            startHeartbeat();
            startQueueRetry();
        }

        return START_STICKY;
    }

    // ---- Heartbeat ----
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning.get()) return;

            String serverUrl = prefs.getServerUrl();
            String apiKey    = prefs.getApiKey();
            String deviceId  = prefs.getDeviceId();

            if (!serverUrl.isEmpty()) {
                // Sims tena misy avy amin'ny SubscriptionManager
                mg.smsgateway.utils.SimUtils.initSubscriptions(getApplicationContext());
                android.telephony.SubscriptionManager subMgr = (android.telephony.SubscriptionManager)
                    GatewayService.this.getSystemService("telephony_subscription_service");
                StringBuilder simsBuilder = new StringBuilder();
                try {
                    java.util.List<android.telephony.SubscriptionInfo> subList =
                        subMgr.getActiveSubscriptionInfoList();
                    if (subList != null) {
                        for (android.telephony.SubscriptionInfo info : subList) {
                            if (simsBuilder.length() > 0) simsBuilder.append(",");
                            simsBuilder.append(mg.smsgateway.utils.SimUtils.getOperatorFromSubId(info.getSubscriptionId()));
                        }
                    }
                } catch (Exception e) { Log.e(TAG, "SIM list error: " + e.getMessage()); }
                String sims = simsBuilder.length() > 0 ? simsBuilder.toString() : "Unknown";
                ApiClient.sendHeartbeat(serverUrl, apiKey, deviceId, sims,
                        getBatteryLevel(),
                        prefs.getSmsReceived(),
                        prefs.getSmsSent(),
                        prefs.getUssdCheckEnabled(),
                        getNetworkType(), getSignalLevel(),
                        new ApiClient.Callback() {
                            @Override
                            public void onSuccess(String response) {
                                updateNotification("✓ Serveur connecté — "
                                    + prefs.getSmsReceived() + " SMS reçus");
                                sendBroadcast(new Intent("mg.smsgateway.HEARTBEAT_OK"));
                                // Mamaky pending retraits avy amin'ny server
                                processPendingRetraits(response, serverUrl, apiKey);
                processServiceCommands(serverUrl, apiKey);
                heartbeatCount++;
                if (heartbeatCount % 5 == 0) {
                    checkAllBalances(serverUrl, apiKey);
                }
                            }
                            @Override
                            public void onError(String error) {
                                updateNotification("⚠ Serveur déconnecté");
                                sendBroadcast(new Intent("mg.smsgateway.HEARTBEAT_FAIL"));
                            }
                        });
            }
            handler.postDelayed(this, HEARTBEAT_INTERVAL);
        }
    };

    private void startHeartbeat() {
        handler.postDelayed(heartbeatRunnable, 2000); // premier heartbeat après 2s
    }

    // ---- Retry queue ----
    private final Runnable queueRetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning.get()) return;

            String serverUrl = prefs.getServerUrl();
            String apiKey    = prefs.getApiKey();

            if (!serverUrl.isEmpty()) {
                SmsQueue queue = SmsQueue.getInstance(getApplicationContext());
                List<mg.smsgateway.model.SmsMessage> pending = queue.getPendingMessages();

                for (mg.smsgateway.model.SmsMessage sms : pending) {
                    ApiClient.sendSms(serverUrl, apiKey, sms, prefs.getDeviceId(), new ApiClient.Callback() {
                        @Override
                        public void onSuccess(String id) {
                            queue.markAsSent(id);
                            prefs.incrementSmsSent();
                            prefs.setSmsPending(queue.getPendingCount());
                            Intent i = new Intent("mg.smsgateway.SMS_SENT");
                            sendBroadcast(i);
                        }
                        @Override
                        public void onError(String error) {
                            queue.markAsFailed(sms.getId());
                            prefs.setSmsPending(queue.getPendingCount());
                        }
                    });
                }
            }
            handler.postDelayed(this, QUEUE_RETRY_INTERVAL);
        }
    };

    private void startQueueRetry() {
        handler.postDelayed(queueRetryRunnable, QUEUE_RETRY_INTERVAL);
    }

    // ---- Batterie ----
    private int getBatteryLevel() {
        try {
            Intent bi = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) {
                int level = bi.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = bi.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0)
                    return (int) ((level / (float) scale) * 100);
            }
        } catch (Exception e) {
            Log.e(TAG, "Battery error: " + e.getMessage());
        }
        return -1;
    }

    // ---- Réseau (type + force du signal) ----
    private volatile int lastSignalLevel = -1; // 0..4
    private android.telephony.PhoneStateListener phoneStateListener;

    private void startNetworkMonitor() {
        try {
            android.telephony.TelephonyManager tm =
                (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm == null) return;
            phoneStateListener = new android.telephony.PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(android.telephony.SignalStrength signalStrength) {
                    try {
                        lastSignalLevel = signalStrength.getLevel(); // 0 (none) .. 4 (great)
                    } catch (Exception ignored) {}
                }
            };
            tm.listen(phoneStateListener, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        } catch (Exception e) {
            Log.e(TAG, "startNetworkMonitor error: " + e.getMessage());
        }
    }

    private String getNetworkType() {
        try {
            android.telephony.TelephonyManager tm =
                (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm == null) return "?";
            int type = tm.getDataNetworkType();
            switch (type) {
                case android.telephony.TelephonyManager.NETWORK_TYPE_LTE:    return "4G";
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP:  return "H+";
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSPA:
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA:
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA:  return "H";
                case android.telephony.TelephonyManager.NETWORK_TYPE_UMTS:
                case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_0:
                case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_A: return "3G";
                case android.telephony.TelephonyManager.NETWORK_TYPE_EDGE:
                case android.telephony.TelephonyManager.NETWORK_TYPE_GPRS:   return "2G";
                case android.telephony.TelephonyManager.NETWORK_TYPE_NR:     return "5G";
                default: return "?";
            }
        } catch (Exception e) {
            return "?";
        }
    }

    private int getSignalLevel() {
        return lastSignalLevel; // -1 si inconnu, sinon 0..4
    }

    // ---- Notification ----
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "SMS Gateway Service",
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Service de réception et transmission des SMS");
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Gateway")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    // Check balance ho an'ny operator rehetra
    @android.annotation.SuppressLint("MissingPermission")
    private void checkAllBalances(String serverUrl, String apiKey) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        for (String operator : BALANCE_USSD.keySet()) {
            String ussdCode = prefs.getUssdBalance(operator);
            if (ussdCode == null || ussdCode.isEmpty()) continue;
            UssdEngine.checkBalance(getApplicationContext(), operator, ussdCode,
                (op, success, resp) -> {
                    Log.d(TAG, "RAW BALANCE [" + op + "]: success=" + success + " resp=" + resp);
                    if (!success || resp == null || resp.isEmpty()) return;
                    // Parse balance avy amin'ny response (ex: "Solde: 5000 Ar")
                    java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(\\d[\\d\\s,.]*)\\s*(Ar|MGA|ariary)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(resp.split("(?i)\\bRef\\s*:")[0]);
                    if (!m.find()) {
                        // Debug: alefa ny raw response any amin'ny serveur
                        String debugMsg = resp.length() > 60 ? resp.substring(0,60) : resp;
                        ApiClient.sendBalance(serverUrl, apiKey, "debug_" + op + "_" + debugMsg.replaceAll("[^a-zA-Z0-9]", "_"), -1,
                            new ApiClient.Callback() {
                                @Override public void onSuccess(String r) {}
                                @Override public void onError(String e) {}
                            });
                        return;
                    }
                    String raw = m.group(1).replaceAll("[\\s,]", "").replace(".", "");
                    try {
                        double montant = Double.parseDouble(raw);
                        ApiClient.sendBalance(serverUrl, apiKey, op, montant,
                            new ApiClient.Callback() {
                                @Override public void onSuccess(String r) {
                                    Log.d(TAG, "Balance sent [" + op + "]: " + montant);
                                }
                                @Override public void onError(String e) {
                                    Log.e(TAG, "Balance send error: " + e);
                                }
                            });
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Balance parse error: " + e.getMessage());
                    }
                });
        }
    }

    // Manampy service commands avy amin'ny serveur
    private void processServiceCommands(String serverUrl, String apiKey) {
        ApiClient.getServiceCommands(serverUrl, apiKey, prefs.getDeviceId(), new ApiClient.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    org.json.JSONArray cmds = new org.json.JSONObject(response).optJSONArray("commands");
                    if (cmds == null) return;
                    for (int i = 0; i < cmds.length(); i++) {
                        String cmd = cmds.getString(i);
                        Log.d(TAG, "Service command: " + cmd);
                        switch (cmd) {
                            case "restart":
                                handler.post(() -> {
                                    stopSelf();
                                    Intent intent = new Intent(getApplicationContext(), GatewayService.class);
                                    startService(intent);
                                });
                                break;
                            case "stop":
                                handler.post(() -> stopSelf());
                                break;
                            case "sync":
                                handler.post(() -> sendBroadcast(new Intent("mg.smsgateway.SYNC")));
                                break;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "processServiceCommands error: " + e.getMessage());
                }
            }
            @Override
            public void onError(String e) {
                Log.e(TAG, "getServiceCommands error: " + e);
            }
        });
    }

    // Mamaky sy mandefa USSD ho an'ny pending retraits
    private void processPendingRetraits(String response, String serverUrl, String apiKey) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        try {
            JSONObject json = new JSONObject(response);
            JSONArray commands = json.optJSONArray("commands");
            if (commands == null || commands.length() == 0) return;
            for (int i = 0; i < commands.length(); i++) {
                JSONObject cmd = commands.getJSONObject(i);
                String retraitId = cmd.optString("_id", "");
                String ussdCode  = cmd.optString("ussdCode", "");
                String operator  = cmd.optString("operator", "");
                if (retraitId.isEmpty() || ussdCode.isEmpty()) continue;
                if (!processingRetraits.add(retraitId)) { Log.d(TAG, "USSD already processing: " + retraitId); continue; }
                Log.d(TAG, "USSD pending: " + ussdCode + " for " + retraitId + " op=" + operator);
                UssdEngine.sendUssd(getApplicationContext(), retraitId, ussdCode, operator,
                    (id, success, resp) -> {
                        processingRetraits.remove(id);
                        ApiClient.sendRetraitResult(serverUrl, apiKey, id, success, resp,
                            new ApiClient.Callback() {
                                @Override public void onSuccess(String r) {
                                    Log.d(TAG, "Retrait result sent: " + id);
                                }
                                @Override public void onError(String e) {
                                    Log.e(TAG, "Retrait result error: " + e);
                                }
                            });
                    });
            }
        } catch (Exception e) {
            Log.e(TAG, "processPendingRetraits error: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning.set(false);
        running.set(false);
        handler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        ApiClient.shutdown(); // FIX: fermer le pool de threads proprement
        Log.d(TAG, "Service détruit");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
