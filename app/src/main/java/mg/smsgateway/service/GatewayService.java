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

        // L'alarme de consultation de solde est portee par le systeme et ne
        // survit ni a un arret force ni a une mise a jour de l'APK. Le service
        // etant le seul composant qui tourne en permanence, c'est lui qui doit
        // la remettre en place — sans dependre de l'ouverture de l'interface.
        try {
            Prefs pSolde = new Prefs(getApplicationContext());
            if (pSolde.getUssdCheckEnabled()) {
                UssdBalanceScheduler.start(getApplicationContext());
                Log.d(TAG, "consultation de solde : alarme confirmee");
            }
        } catch (Exception e) {
            Log.e(TAG, "reprise alarme solde: " + e.getMessage());
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

            // Une SIM comorienne presente marque DEFINITIVEMENT l'appareil (KM
            // dans son identifiant). Le serveur s'appuie sur cette marque pour
            // lui envoyer les retraits Comores et classer ses SMS. Verifie a
            // chaque battement : la SIM peut etre inseree apres l'installation.
            mg.smsgateway.utils.SimUtils.initSubscriptions(getApplicationContext());
            if (mg.smsgateway.utils.SimUtils.aSimComores(getApplicationContext())
                    && prefs.marquerAppareilComores()) {
                Log.d(TAG, "SIM Comores detectee — appareil marque KM : "
                    + prefs.getDeviceId());
            }

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
                // NOTE: L'ancien controle de solde (checkAllBalances) a ete retire.
                // Il interrogeait l'operateur par USSD "one-shot" toutes les 5
                // battements, sur le fil principal — ce qui bloquait l'application
                // (ANR "l'application ne repond pas") et remontait des reponses
                // inexploitables ("UNKNOWN APPLICATION", "failed: -1") ensuite
                // enregistrees comme faux soldes. La consultation de solde passe
                // desormais UNIQUEMENT par UssdBalanceScheduler -> UssdQueue ->
                // lireSoldeUssd (lecture d'ecran fiable), declenchee apres un
                // mouvement ou selon le reglage "Codes USSD Solde".
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

    // ------------------------------------------------------------------
    // checkAllBalances() a ete RETIRE (voie "one-shot" defaillante).
    // ------------------------------------------------------------------
    // Cette methode interrogeait chaque operateur par sendUssdRequest() une
    // fois toutes les 5 battements de heartbeat. Deux defauts majeurs :
    //   1) Sur les menus qui attendent une saisie (Orange #144, MVola #111),
    //      la reponse "one-shot" est vide ou "UNKNOWN APPLICATION" / "failed:-1".
    //      Elle etait pourtant renvoyee au serveur comme un solde -> faux soldes
    //      et valeurs -1 dans l'admin.
    //   2) L'enchainement d'appels USSD sur le fil principal figeait
    //      l'application ("l'application ne repond pas" / fermeture).
    // La lecture de solde fiable passe desormais par UssdBalanceScheduler ->
    // UssdQueue -> UssdEngine.lireSoldeUssd (voie silencieuse validee, sinon
    // lecture d'ecran). Voir onStartCommand (UssdBalanceScheduler.start).
    // ------------------------------------------------------------------

    // Manampy service commands avy amin'ny serveur
    private void processServiceCommands(String serverUrl, String apiKey) {
        ApiClient.getServiceCommands(serverUrl, apiKey, prefs.getDeviceId(), new ApiClient.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    org.json.JSONArray cmds = new org.json.JSONObject(response).optJSONArray("commands");
                    if (cmds == null) return;
                    for (int i = 0; i < cmds.length(); i++) {
                        Object raw = cmds.get(i);

                        // FIX: command Object (ussd_retrait) vs command String legacy
                        if (raw instanceof org.json.JSONObject) {
                            org.json.JSONObject obj = (org.json.JSONObject) raw;
                            String type = obj.optString("type", "");
                            if ("ussd_retrait".equals(type)) {
                                String retraitId = obj.optString("retraitId", "");
                                String ussdCode  = obj.optString("ussdCode", "");
                                String operator  = obj.optString("operator", "");
                                // PIN a saisir a l'invite (Orange). Vide = PIN deja dans le code.
                                String ussdPin   = obj.optString("ussdPin", "");
                                // Reponse a taper sur un ecran de saisie qui n'est PAS
                                // une demande de PIN, et nombre d'ecrans attendus
                                // (Orange Money : 2 ; MVola : PIN deja dans le code).
                                String menuReply = obj.optString("menuReply", "");
                                int maxSteps     = obj.optInt("maxSteps", 1);
                                long gapMs       = obj.optLong("gapMs", 0L);
                                Log.d(TAG, "USSD retrait command: " + operator + " -> " + ussdCode
                                        + (ussdPin.isEmpty() ? "" : " [PIN separe, "
                                          + maxSteps + " ecran(s)]"));
                                executeUssdRetrait(serverUrl, apiKey, retraitId, ussdCode,
                                        operator, ussdPin, menuReply, maxSteps, gapMs);
                            }
                            continue;
                        }

                        String cmd = String.valueOf(raw);
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


    // FIX: manatanteraka ny code USSD retrait nalefan'ny backend (server-side)
    @android.annotation.SuppressLint("MissingPermission")
    private void executeUssdRetrait(String serverUrl, String apiKey,
                                     String retraitId, String ussdCode, String operator) {
        executeUssdRetrait(serverUrl, apiKey, retraitId, ussdCode, operator, null);
    }

    private void executeUssdRetrait(String serverUrl, String apiKey,
                                     String retraitId, String ussdCode, String operator,
                                     String ussdPin) {
        executeUssdRetrait(serverUrl, apiKey, retraitId, ussdCode, operator, ussdPin, "", 1);
    }

    /**
     * N'execute RIEN directement : depose le retrait dans {@link UssdQueue}, qui
     * garantit un seul USSD a la fois et une pause entre deux retraits. Deux
     * retraits simultanes sur la meme SIM se detruiraient mutuellement et
     * pourraient envoyer l'argent au mauvais numero.
     */
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
        if (retraitId == null || retraitId.isEmpty() || ussdCode == null || ussdCode.isEmpty()) return;

        final boolean pinSepare = ussdPin != null && !ussdPin.trim().isEmpty();

        UssdEngine.UssdCallback cb =
            (id, success, response) -> {
                Log.d(TAG, "USSD retrait result [" + operator + "] success=" + success + " resp=" + response);
                // pinSubmitted au sens serveur = "la transaction est bien partie".
                // Un transfert confirme par l'operateur compte comme tel, meme si
                // le dernier ecran (repertoire telephonique) n'a pas ete rempli.
                boolean pinOk = success
                        || (pinSepare && UssdEngine.lastPinSubmitted);

                // Le solde vient de changer : on le relit a la source plutot
                // que de l'estimer. La consultation passe par la file, elle ne
                // peut donc pas perturber le retrait suivant.
                if (success) {
                    UssdBalanceScheduler.apresMouvement(getApplicationContext(), operator);
                }
                // 'response' = texte operateur brut ; 'motif' = explication technique.
                String motif = pinSepare ? UssdEngine.lastMotif : "";
                ApiClient.sendUssdRetraitResult(serverUrl, apiKey, id, success, response, pinOk, motif,
                    new ApiClient.Callback() {
                        @Override public void onSuccess(String r) {
                            Log.d(TAG, "ussd-result envoye OK pour " + id);
                        }
                        @Override public void onError(String err) {
                            Log.e(TAG, "ussd-result envoi echec: " + err);
                        }
                    });
            };

        UssdQueue.enqueue(getApplicationContext(), new UssdQueue.Job(
                retraitId, ussdCode, operator, ussdPin, menuReply, maxSteps, gapMs, cb));
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
                String ussdPin   = cmd.optString("ussdPin", "");
                if (retraitId.isEmpty() || ussdCode.isEmpty()) continue;
                String menuReply = cmd.optString("menuReply", "");
                int maxSteps     = cmd.optInt("maxSteps", 1);
                Log.d(TAG, "USSD pending: " + ussdCode + " for " + retraitId + " op=" + operator
                        + (ussdPin.isEmpty() ? "" : " [PIN separe]"));
                // Meme file que l'autre canal : c'est elle qui protege contre les
                // executions simultanees, quelle que soit la voie d'arrivee.
                executeUssdRetrait(serverUrl, apiKey, retraitId, ussdCode, operator,
                        ussdPin, menuReply, maxSteps);
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
