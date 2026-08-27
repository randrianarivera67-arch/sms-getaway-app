package mg.smsgateway.network;

import android.util.Log;
import mg.smsgateway.model.SmsMessage;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ApiClient — VERSION ULTRA PRO ROBOT
 *
 * FIX appliqués:
 *  1. ensureExecutor() — recrée l'executor si shutdown (BUG KRITIKA corrigé)
 *  2. AtomicReference<ExecutorService> — thread-safe
 *  3. Retry automatique HTTP 5xx (3 essais avec backoff)
 *  4. Timeout augmenté à 20s pour les connexions lentes Madagascar
 */
public class ApiClient {

    private static final String TAG      = "ApiClient";
    private static final int    TIMEOUT  = 20_000; // 20s — réseau Madagascar
    private static final int    MAX_THREADS = 4;
    private static final int    MAX_RETRY   = 3;   // FIX 3: retry HTTP 5xx

    // FIX 1+2: AtomicReference — thread-safe, recrée si shutdown
    private static final AtomicReference<ExecutorService> executorRef =
        new AtomicReference<>(Executors.newFixedThreadPool(MAX_THREADS));

    /**
     * FIX KRITIKA: Recrée l'executor s'il a été shutdown.
     * À appeler avant chaque soumission de tâche réseau.
     */
    public static void ensureExecutor() {
        ExecutorService current = executorRef.get();
        if (current == null || current.isShutdown() || current.isTerminated()) {
            ExecutorService fresh = Executors.newFixedThreadPool(MAX_THREADS);
            if (executorRef.compareAndSet(current, fresh)) {
                Log.d(TAG, "Executor recréé");
            } else {
                // Un autre thread a déjà recréé → ferme le nôtre
                fresh.shutdownNow();
            }
        }
    }

    private static ExecutorService executor() {
        ensureExecutor();
        return executorRef.get();
    }

    public interface Callback {
        void onSuccess(String id);
        void onError(String error);
    }

    // -----------------------------------------------------------------------
    // Envoi SMS reçu
    // -----------------------------------------------------------------------
    public static void sendSms(String serverUrl, String apiKey,
                               SmsMessage sms, Callback callback) {
        sendSms(serverUrl, apiKey, sms, null, callback);
    }

    public static void sendSms(String serverUrl, String apiKey,
                               SmsMessage sms, String deviceId, Callback callback) {
        executor().submit(() -> {
            String bodyStr = "";
            try {
                bodyStr = (deviceId != null ? sms.toJson(deviceId) : sms.toJson()).toString();
            } catch (Exception e) {
                callback.onError("JSON error: " + e.getMessage());
                return;
            }
            postWithRetry(serverUrl + "/api/sms/receive", apiKey, bodyStr,
                new Callback() {
                    @Override public void onSuccess(String r) { callback.onSuccess(sms.getId()); }
                    @Override public void onError(String e)   { callback.onError(e); }
                });
        });
    }

    // -----------------------------------------------------------------------
    // Heartbeat
    // -----------------------------------------------------------------------
    public static void sendHeartbeat(String serverUrl, String apiKey,
                                     String deviceId, String sims, int battery,
                                     int smsReceived, int smsSent,
                                     boolean ussdCheckEnabled,
                                     String networkType, int signalLevel,
                                     Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/api/device/heartbeat");
                conn = openConnection(url, "POST", apiKey);

                JSONObject body = new JSONObject();
                body.put("deviceId",         deviceId);
                body.put("sims",             sims);
                body.put("battery",          battery);
                body.put("smsReceived",      smsReceived);
                body.put("smsSent",          smsSent);
                body.put("ussdCheckEnabled", ussdCheckEnabled);
                body.put("networkType",      networkType);
                body.put("signalLevel",      signalLevel);
                body.put("timestamp",        System.currentTimeMillis());

                writeBody(conn, body.toString());
                int code = conn.getResponseCode();
                if (code == 200) {
                    callback.onSuccess(readStream(conn.getInputStream()));
                } else {
                    callback.onError("HTTP " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "heartbeat: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Network error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // -----------------------------------------------------------------------
    // Test connexion
    // -----------------------------------------------------------------------
    public static void testConnection(String serverUrl, String apiKey, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/health");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                int code = conn.getResponseCode();
                if (code == 200) callback.onSuccess("ok");
                else callback.onError("HTTP " + code);
            } catch (Exception e) {
                Log.e(TAG, "test: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Connection refused");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // -----------------------------------------------------------------------
    // Fetch stats
    // -----------------------------------------------------------------------
    public static void fetchStats(String serverUrl, String apiKey,
                                  String deviceId, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/api/device/stats?deviceId=" + deviceId);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                int code = conn.getResponseCode();
                if (code == 200) callback.onSuccess(readStream(conn.getInputStream()));
                else callback.onError("HTTP " + code);
            } catch (Exception e) {
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // -----------------------------------------------------------------------
    // Orange wallet
    // -----------------------------------------------------------------------
    public static void setOrangeWallet(String serverUrl, String apiKey,
                                       boolean marchand, Callback callback) {
        executor().submit(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("active", marchand ? "marchand" : "tsotra");
                postWithRetry(serverUrl + "/api/solde/orange-wallet", apiKey,
                    body.toString(), callback);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Solde check
    // -----------------------------------------------------------------------
    public static void sendSoldeCheck(String serverUrl, String apiKey,
                                      String operator, String ussdResponse,
                                      long timestamp, Callback callback) {
        executor().submit(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("operator",     operator);
                body.put("ussdResponse", ussdResponse);
                body.put("timestamp",    timestamp);
                postWithRetry(serverUrl + "/api/solde/check-result", apiKey,
                    body.toString(), callback);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Numero check
    // -----------------------------------------------------------------------
    public static void sendNumeroCheck(String serverUrl, String apiKey,
                                       String operator, String ussdResponse,
                                       long timestamp, Callback callback) {
        executor().submit(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("operator",     operator);
                body.put("ussdResponse", ussdResponse);
                body.put("timestamp",    timestamp);
                postWithRetry(serverUrl + "/api/numero/check-result", apiKey,
                    body.toString(), callback);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    public static void sendNumeroSet(String serverUrl, String apiKey,
                                     String operator, String numero,
                                     Callback callback) {
        executor().submit(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("operator", operator);
                body.put("numero",   numero);
                postWithRetry(serverUrl + "/api/numero/set", apiKey,
                    body.toString(), callback);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Balance legacy
    // -----------------------------------------------------------------------
    public static void sendBalance(String serverUrl, String apiKey,
                                   String operator, double montant, Callback callback) {
        executor().submit(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("operator", operator);
                body.put("montant",  montant);
                postWithRetry(serverUrl + "/api/stats/balance", apiKey,
                    body.toString(), callback);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // USSD retrait result
    // -----------------------------------------------------------------------
    public static void sendUssdRetraitResult(String serverUrl, String apiKey,
                                             String retraitId, boolean success,
                                             String response, Callback callback) {
        sendUssdRetraitResult(serverUrl, apiKey, retraitId, success, response,
            false, callback);
    }

    public static void sendUssdRetraitResult(String serverUrl, String apiKey,
                                             String retraitId, boolean success,
                                             String response, boolean pinSubmitted,
                                             Callback callback) {
        sendUssdRetraitResult(serverUrl, apiKey, retraitId, success, response,
            pinSubmitted, "", callback);
    }

    public static void sendUssdRetraitResult(String serverUrl, String apiKey,
                                             String retraitId, boolean success,
                                             String response, boolean pinSubmitted,
                                             String motif, Callback callback) {
        executor().submit(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("success",      success);
                payload.put("response",     response != null ? response : "");
                payload.put("pinSubmitted", pinSubmitted);
                payload.put("motif",        motif == null ? "" : motif);
                // FIX 3: retry sur 5xx — le réseau Madagascar peut être instable
                postWithRetry(
                    serverUrl + "/api/retrait/" + retraitId + "/ussd-result",
                    apiKey, payload.toString(), callback);
            } catch (Exception e) {
                Log.e(TAG, "sendUssdRetraitResult: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            }
        });
    }

    // -----------------------------------------------------------------------
    // Service commands
    // -----------------------------------------------------------------------
    public static void getServiceCommands(String serverUrl, String apiKey,
                                          String deviceId, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/api/service/commands?deviceId=" + deviceId);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                int code = conn.getResponseCode();
                if (code == 200) callback.onSuccess(readStream(conn.getInputStream()));
                else callback.onError("HTTP " + code);
            } catch (Exception e) {
                Log.e(TAG, "getServiceCommands: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // -----------------------------------------------------------------------
    // FIX 3: POST avec retry automatique (HTTP 5xx, IOException)
    // -----------------------------------------------------------------------
    private static void postWithRetry(String urlStr, String apiKey,
                                      String bodyStr, Callback callback) {
        int attempt = 0;
        Exception lastEx = null;
        while (attempt < MAX_RETRY) {
            attempt++;
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = openConnection(url, "POST", apiKey);
                writeBody(conn, bodyStr);
                int code = conn.getResponseCode();
                if (code == 200 || code == 201) {
                    callback.onSuccess(readStream(conn.getInputStream()));
                    return;
                } else if (code >= 500 && attempt < MAX_RETRY) {
                    // Retry sur erreur serveur
                    Log.w(TAG, "HTTP " + code + " → retry " + attempt + "/" + MAX_RETRY);
                    Thread.sleep(1000L * attempt); // backoff simple
                } else {
                    String err = readStream(conn.getErrorStream());
                    callback.onError("HTTP " + code + (err.isEmpty() ? "" : ": " + err));
                    return;
                }
            } catch (Exception e) {
                lastEx = e;
                if (attempt < MAX_RETRY) {
                    Log.w(TAG, "Tentative " + attempt + " échouée: " + e.getMessage()
                        + " → retry");
                    try { Thread.sleep(1000L * attempt); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        String msg = lastEx != null
            ? (lastEx.getMessage() != null ? lastEx.getMessage() : "Network error")
            : "Max retries exceeded";
        callback.onError(msg);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private static HttpURLConnection openConnection(URL url, String method,
                                                    String apiKey) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        if ("POST".equals(method) || "PUT".equals(method)) conn.setDoOutput(true);
        return conn;
    }

    private static void writeBody(HttpURLConnection conn, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try {
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString().trim();
        } catch (Exception e) { return ""; }
    }

    // -----------------------------------------------------------------------
    // Shutdown propre (appelé uniquement sur arrêt VOLONTAIRE)
    // -----------------------------------------------------------------------
    public static void shutdown() {
        ExecutorService ex = executorRef.getAndSet(null);
        if (ex != null) {
            ex.shutdown();
            try { ex.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
