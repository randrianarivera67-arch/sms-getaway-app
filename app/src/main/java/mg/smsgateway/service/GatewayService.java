package mg.smsgateway.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
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

/**
 * GatewayService — VERSION ULTRA PRO ROBOT
 *
 * Fix appliqués:
 *  1. WakeLock permanent (pas de timeout 12h)
 *  2. AlarmManager keepalive — redémarre le service toutes les 5 min
 *  3. ConnectivityManager.NetworkCallback — reconnect automatique réseau
 *  4. ApiClient.ensureExecutor() — recrée l'executor si shutdown
 *  5. processPendingRetraits séparé du heartbeat — plus de double parse
 *  6. Retry heartbeat exponentiel — 5s, 15s, 30s, 60s max
 */
public class GatewayService extends Service {

    private static final String TAG             = "GatewayService";
    private static final String CHANNEL_ID      = "sms_gateway_channel";
    private static final int    NOTIFICATION_ID = 1001;

    // FIX 1: heartbeat interval réduit + retry exponentiel
    private static final long HEARTBEAT_INTERVAL   = 30_000L;
    private static final long QUEUE_RETRY_INTERVAL = 60_000L;

    // FIX 2: AlarmManager keepalive — réveille le service toutes les 5 min
    private static final String ACTION_KEEPALIVE   = "mg.smsgateway.KEEPALIVE";
    private static final int    KEEPALIVE_REQUEST  = 7777;
    private static final long   KEEPALIVE_INTERVAL = 5 * 60 * 1000L; // 5 min

    public static final AtomicBoolean running = new AtomicBoolean(false);

    private static final java.util.Map<String, String> BALANCE_USSD =
        new java.util.HashMap<String, String>() {{
            put("orange", "#144*5*3*2026#");
            put("mvola",  "#111*1*6*1*2011#");
            put("airtel", "*123#");
        }};

    private final java.util.Set<String> processingRetraits =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private Handler  handler;
    private Prefs    prefs;
    // FIX 1: WakeLock SANS timeout
    private PowerManager.WakeLock wakeLock;
    private final AtomicBoolean   isRunning = new AtomicBoolean(false);

    // FIX 3: retry exponentiel pour heartbeat
    private int  heartbeatFailCount   = 0;
    private long currentHeartbeatDelay = HEARTBEAT_INTERVAL;
    private static final long HEARTBEAT_MIN = 10_000L;
    private static final long HEARTBEAT_MAX = 120_000L;

    // FIX 4: ConnectivityManager.NetworkCallback
    private ConnectivityManager       connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean          networkAvailable = true;

    // -----------------------------------------------------------------------
    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        prefs   = new Prefs(this);
        createNotificationChannel();
        startNetworkMonitor();

        // FIX 1: WakeLock PERMANENT — sans timeout
        // Le service est un foreground service : Android ne le tue pas.
        // Le WakeLock empêche le CPU de s'endormir entre les heartbeats.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SMSGateway:WakeLock");
            // Pas de timeout — le service gère lui-même son cycle de vie.
        }

        // FIX 4: ConnectivityManager callback — reconnect auto
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        registerNetworkCallback();
    }

    // -----------------------------------------------------------------------
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Keepalive alarm — réveille si le handler s'est arrêté
        if (intent != null && ACTION_KEEPALIVE.equals(intent.getAction())) {
            Log.d(TAG, "Keepalive reçu");
            if (!isRunning.get()) {
                Log.w(TAG, "Service non running au keepalive → restart");
                startEverything();
            } else {
                // Repose l'alarme keepalive
                scheduleKeepalive();
            }
            return START_STICKY;
        }

        if (intent != null && "STOP".equals(intent.getAction())) {
            stopEverything();
            return START_NOT_STICKY;
        }

        // Remet l'alarme de consultation de solde
        try {
            if (new Prefs(getApplicationContext()).getUssdCheckEnabled()) {
                UssdBalanceScheduler.start(getApplicationContext());
                Log.d(TAG, "consultation de solde : alarme confirmée");
            }
        } catch (Exception e) {
            Log.e(TAG, "reprise alarme solde: " + e.getMessage());
        }

        if (isRunning.compareAndSet(false, true)) {
            startEverything();
        }

        return START_STICKY;
    }

    private void startEverything() {
        startForeground(NOTIFICATION_ID, buildNotification("Service actif…"));
        running.set(true);

        // FIX 1: WakeLock SANS timeout
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(); // pas de timeout
        }

        isRunning.set(true);
        heartbeatFailCount    = 0;
        currentHeartbeatDelay = HEARTBEAT_INTERVAL;

        startHeartbeat();
        startQueueRetry();
        scheduleKeepalive();
    }

    private void stopEverything() {
        isRunning.set(false);
        running.set(false);
        handler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        cancelKeepalive();
        unregisterNetworkCallback();
        ApiClient.shutdown();
        stopSelf();
    }

    // -----------------------------------------------------------------------
    // FIX 2: AlarmManager keepalive
    // -----------------------------------------------------------------------
    private void scheduleKeepalive() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(this, GatewayService.class);
        i.setAction(ACTION_KEEPALIVE);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getService(this, KEEPALIVE_REQUEST, i, flags);
        long trigger = SystemClock.elapsedRealtime() + KEEPALIVE_INTERVAL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        } else {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        }
        Log.d(TAG, "Keepalive planifié dans " + (KEEPALIVE_INTERVAL / 60000) + " min");
    }

    private void cancelKeepalive() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, GatewayService.class);
            i.setAction(ACTION_KEEPALIVE);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getService(this, KEEPALIVE_REQUEST, i, flags);
            am.cancel(pi);
        } catch (Exception e) { Log.e(TAG, "cancelKeepalive: " + e.getMessage()); }
    }

    // -----------------------------------------------------------------------
    // FIX 4: ConnectivityManager.NetworkCallback — reconnect auto réseau
    // -----------------------------------------------------------------------
    private void registerNetworkCallback() {
        if (connectivityManager == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    Log.d(TAG, "Réseau disponible → restart heartbeat immédiat");
                    networkAvailable = true;
                    heartbeatFailCount    = 0;
                    currentHeartbeatDelay = HEARTBEAT_MIN;
                    handler.removeCallbacks(heartbeatRunnable);
                    handler.post(heartbeatRunnable); // heartbeat immédiat
                }
                @Override
                public void onLost(@NonNull Network network) {
                    Log.w(TAG, "Réseau perdu");
                    networkAvailable = false;
                    updateNotification("⚠ Réseau indisponible — en attente...");
                }
            };
            connectivityManager.registerNetworkCallback(req, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "registerNetworkCallback: " + e.getMessage());
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (Exception ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // Heartbeat — FIX 3: retry exponentiel
    // -----------------------------------------------------------------------
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning.get()) return;

            // FIX: recrée executor si shutdown
            ApiClient.ensureExecutor();

            String serverUrl = prefs.getServerUrl();
            String apiKey    = prefs.getApiKey();
            String deviceId  = prefs.getDeviceId();

            if (serverUrl.isEmpty()) {
                handler.postDelayed(this, HEARTBEAT_INTERVAL);
                return;
            }

            mg.smsgateway.utils.SimUtils.initSubscriptions(getApplicationContext());
            android.telephony.SubscriptionManager subMgr =
                (android.telephony.SubscriptionManager)
                getSystemService("telephony_subscription_service");
            StringBuilder simsBuilder = new StringBuilder();
            try {
                java.util.List<android.telephony.SubscriptionInfo> subList =
                    subMgr.getActiveSubscriptionInfoList();
                if (subList != null) {
                    for (android.telephony.SubscriptionInfo info : subList) {
                        if (simsBuilder.length() > 0) simsBuilder.append(",");
                        simsBuilder.append(
                            mg.smsgateway.utils.SimUtils.getOperatorFromSubId(
                                info.getSubscriptionId()));
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
                        // FIX 3: reset retry sur succès
                        heartbeatFailCount    = 0;
                        currentHeartbeatDelay = HEARTBEAT_INTERVAL;
                        updateNotification("✓ Connecté — "
                            + prefs.getSmsReceived() + " SMS reçus");
                        sendBroadcast(new Intent("mg.smsgateway.HEARTBEAT_OK"));
                        processPendingRetraits(response, serverUrl, apiKey);
                        processServiceCommands(serverUrl, apiKey);
                        // Repose keepalive
                        scheduleKeepalive();
                    }
                    @Override
                    public void onError(String error) {
                        // FIX 3: retry exponentiel
                        heartbeatFailCount++;
                        currentHeartbeatDelay = Math.min(
                            HEARTBEAT_MIN * (long) Math.pow(2, heartbeatFailCount),
                            HEARTBEAT_MAX);
                        Log.w(TAG, "Heartbeat échec #" + heartbeatFailCount
                            + " → retry dans " + (currentHeartbeatDelay / 1000) + "s");
                        updateNotification("⚠ Déconnecté — retry dans "
                            + (currentHeartbeatDelay / 1000) + "s");
                        sendBroadcast(new Intent("mg.smsgateway.HEARTBEAT_FAIL"));
                    }
                });

            handler.postDelayed(this, currentHeartbeatDelay);
        }
    };

    private void startHeartbeat() {
        handler.postDelayed(heartbeatRunnable, 2000);
    }

    // -----------------------------------------------------------------------
    // Queue retry SMS
    // -----------------------------------------------------------------------
    private final Runnable queueRetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning.get()) return;
            String serverUrl = prefs.getServerUrl();
            String apiKey    = prefs.getApiKey();
            if (!serverUrl.isEmpty()) {
                ApiClient.ensureExecutor(); // FIX
                SmsQueue queue = SmsQueue.getInstance(getApplicationContext());
                List<mg.smsgateway.model.SmsMessage> pending = queue.getPendingMessages();
                for (mg.smsgateway.model.SmsMessage sms : pending) {
                    ApiClient.sendSms(serverUrl, apiKey, sms, prefs.getDeviceId(),
                        new ApiClient.Callback() {
                            @Override public void onSuccess(String id) {
                                queue.markAsSent(id);
                                prefs.incrementSmsSent();
                                prefs.setSmsPending(queue.getPendingCount());
                                sendBroadcast(new Intent("mg.smsgateway.SMS_SENT"));
                            }
                            @Override public void onError(String error) {
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

    // -----------------------------------------------------------------------
    // Batterie
    // -----------------------------------------------------------------------
    private int getBatteryLevel() {
        try {
            Intent bi = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) {
                int level = bi.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = bi.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) return (int) ((level / (float) scale) * 100);
            }
        } catch (Exception e) { Log.e(TAG, "Battery: " + e.getMessage()); }
        return -1;
    }

    // -----------------------------------------------------------------------
    // Réseau
    // -----------------------------------------------------------------------
    private volatile int lastSignalLevel = -1;
    private android.telephony.PhoneStateListener phoneStateListener;

    private void startNetworkMonitor() {
        try {
            android.telephony.TelephonyManager tm =
                (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm == null) return;
            phoneStateListener = new android.telephony.PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(android.telephony.SignalStrength s) {
                    try { lastSignalLevel = s.getLevel(); } catch (Exception ignored) {}
                }
            };
            tm.listen(phoneStateListener,
                android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        } catch (Exception e) { Log.e(TAG, "startNetworkMonitor: " + e.getMessage()); }
    }

    private String getNetworkType() {
        try {
            android.telephony.TelephonyManager tm =
                (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm == null) return "?";
            int type = tm.getDataNetworkType();
            switch (type) {
                case android.telephony.TelephonyManager.NETWORK_TYPE_LTE:   return "4G";
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP: return "H+";
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSPA:
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA:
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA: return "H";
                case android.telephony.TelephonyManager.NETWORK_TYPE_UMTS:
                case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_0:
                case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_A: return "3G";
                case android.telephony.TelephonyManager.NETWORK_TYPE_EDGE:
                case android.telephony.TelephonyManager.NETWORK_TYPE_GPRS:  return "2G";
                case android.telephony.TelephonyManager.NETWORK_TYPE_NR:    return "5G";
                default: return "?";
            }
        } catch (Exception e) { return "?"; }
    }

    private int getSignalLevel() { return lastSignalLevel; }

    // -----------------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------------
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "SMS Gateway Service",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Service de réception et transmission des SMS");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
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

    // -----------------------------------------------------------------------
    // Service commands
    // -----------------------------------------------------------------------
    private void processServiceCommands(String serverUrl, String apiKey) {
        ApiClient.getServiceCommands(serverUrl, apiKey, prefs.getDeviceId(),
            new ApiClient.Callback() {
                @Override public void onSuccess(String response) {
                    try {
                        org.json.JSONArray cmds =
                            new org.json.JSONObject(response).optJSONArray("commands");
                        if (cmds == null) return;
                        for (int i = 0; i < cmds.length(); i++) {
                            Object raw = cmds.get(i);
                            if (raw instanceof org.json.JSONObject) {
                                org.json.JSONObject obj = (org.json.JSONObject) raw;
                                String type = obj.optString("type", "");
                                if ("ussd_retrait".equals(type)) {
                                    String retraitId = obj.optString("retraitId", "");
                                    String ussdCode  = obj.optString("ussdCode", "");
                                    String operator  = obj.optString("operator", "");
                                    String ussdPin   = obj.optString("ussdPin", "");
                                    String menuReply = obj.optString("menuReply", "");
                                    int maxSteps     = obj.optInt("maxSteps", 1);
                                    long gapMs       = obj.optLong("gapMs", 0L);
                                    executeUssdRetrait(serverUrl, apiKey, retraitId,
                                        ussdCode, operator, ussdPin, menuReply, maxSteps, gapMs);
                                }
                                continue;
                            }
                            String cmd = String.valueOf(raw);
                            switch (cmd) {
                                case "restart":
                                    handler.post(() -> {
                                        stopSelf();
                                        Intent si = new Intent(getApplicationContext(),
                                            GatewayService.class);
                                        startService(si);
                                    });
                                    break;
                                case "stop":
                                    handler.post(() -> stopSelf());
                                    break;
                                case "sync":
                                    handler.post(() ->
                                        sendBroadcast(new Intent("mg.smsgateway.SYNC")));
                                    break;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "processServiceCommands: " + e.getMessage());
                    }
                }
                @Override public void onError(String e) {
                    Log.e(TAG, "getServiceCommands error: " + e);
                }
            });
    }

    // -----------------------------------------------------------------------
    // USSD retrait
    // -----------------------------------------------------------------------
    @android.annotation.SuppressLint("MissingPermission")
    private void executeUssdRetrait(String serverUrl, String apiKey,
            String retraitId, String ussdCode, String operator) {
        executeUssdRetrait(serverUrl, apiKey, retraitId, ussdCode, operator, null);
    }

    private void executeUssdRetrait(String serverUrl, String apiKey,
            String retraitId, String ussdCode, String operator, String ussdPin) {
        executeUssdRetrait(serverUrl, apiKey, retraitId, ussdCode, operator, ussdPin, "", 1);
    }

    private void executeUssdRetrait(String serverUrl, String apiKey,
            String retraitId, String ussdCode, String operator,
            String ussdPin, String menuReply, int maxSteps) {
        executeUssdRetrait(serverUrl, apiKey, retraitId, ussdCode, operator,
            ussdPin, menuReply, maxSteps, 0L);
    }

    private void executeUssdRetrait(String serverUrl, String apiKey,
            String retraitId, String ussdCode, String operator,
            String ussdPin, String menuReply, int maxSteps, long gapMs) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        if (retraitId == null || retraitId.isEmpty()
                || ussdCode == null || ussdCode.isEmpty()) return;

        final boolean pinSepare = ussdPin != null && !ussdPin.trim().isEmpty();

        UssdEngine.UssdCallback cb = (id, success, response) -> {
            Log.d(TAG, "USSD retrait [" + operator + "] success=" + success);
            boolean pinOk = success || (pinSepare && UssdEngine.lastPinSubmitted);
            if (success) {
                UssdBalanceScheduler.apresMouvement(getApplicationContext(), operator);
            }
            String motif = pinSepare ? UssdEngine.lastMotif : "";
            ApiClient.sendUssdRetraitResult(serverUrl, apiKey, id, success,
                response, pinOk, motif, new ApiClient.Callback() {
                    @Override public void onSuccess(String r) {
                        Log.d(TAG, "ussd-result OK pour " + id);
                    }
                    @Override public void onError(String err) {
                        Log.e(TAG, "ussd-result échec: " + err);
                    }
                });
        };

        UssdQueue.enqueue(getApplicationContext(), new UssdQueue.Job(
            retraitId, ussdCode, operator, ussdPin, menuReply, maxSteps, gapMs, cb));
    }

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
                String ussdPin   = cmd.optString("ussdPin", "");
                if (retraitId.isEmpty() || ussdCode.isEmpty()) continue;
                String menuReply = cmd.optString("menuReply", "");
                int maxSteps     = cmd.optInt("maxSteps", 1);
                executeUssdRetrait(serverUrl, apiKey, retraitId, ussdCode,
                    operator, ussdPin, menuReply, maxSteps);
            }
        } catch (Exception e) {
            Log.e(TAG, "processPendingRetraits: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning.set(false);
        running.set(false);
        handler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        unregisterNetworkCallback();
        // FIX: ne PAS appeler ApiClient.shutdown() ici — le service redémarre
        // immédiatement via START_STICKY et a besoin de l'executor.
        // shutdown() est appelé uniquement sur arrêt volontaire (action STOP).
        Log.d(TAG, "Service détruit — redémarrage automatique attendu");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
