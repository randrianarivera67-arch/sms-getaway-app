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

public class ApiClient {

    private static final String TAG     = "ApiClient";
    private static final int TIMEOUT    = 15000;
    private static final int MAX_THREADS= 4;

    /**
     * CRASH CORRIGE ICI — preuve : dropbox 2026-08-25 17:53
     *   RejectedExecutionException ... ThreadPoolExecutor[Terminated]
     *     at ApiClient.sendHeartbeat(ApiClient.java:78)
     *     at GatewayService$2.run(GatewayService.java:129)
     * Le pool etait "static final" : apres shutdown() appele par
     * GatewayService.onDestroy(), il restait Terminated pour toute la vie du
     * processus. Le service redemarre aussitot (START_STICKY), son heartbeat
     * rappelle submit() -> exception non capturee -> l'app se ferme.
     */
    private static final AtomicReference<ExecutorService> EXECUTOR =
            new AtomicReference<>(Executors.newFixedThreadPool(MAX_THREADS));

    /** Pool utilisable, recree si un shutdown precedent l'a arrete. */
    private static ExecutorService executor() {
        ExecutorService e = EXECUTOR.get();
        if (e == null || e.isShutdown() || e.isTerminated()) {
            ExecutorService neuf = Executors.newFixedThreadPool(MAX_THREADS);
            if (EXECUTOR.compareAndSet(e, neuf)) {
                Log.d(TAG, "pool de threads recree");
                return neuf;
            }
            neuf.shutdownNow();
            return EXECUTOR.get();
        }
        return e;
    }

    public interface Callback {
        void onSuccess(String id);
        void onError(String error);
    }

    // ---- Envoi SMS reçu vers le serveur ----
    public static void sendSms(String serverUrl, String apiKey,
                               SmsMessage sms, Callback callback) {
        sendSms(serverUrl, apiKey, sms, null, callback);
    }

    // FIX: overload avec deviceId — assure que le SMS est lie au bon appareil
    public static void sendSms(String serverUrl, String apiKey,
                               SmsMessage sms, String deviceId, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/api/sms/receive");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setDoOutput(true);

                byte[] input = (deviceId != null ? sms.toJson(deviceId) : sms.toJson())
                    .toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(input); }

                int code = conn.getResponseCode();
                if (code == 200 || code == 201) {
                    callback.onSuccess(sms.getId());
                } else {
                    // Lire le corps de l'erreur
                    String body = readStream(conn.getErrorStream());
                    callback.onError("HTTP " + code + (body.isEmpty() ? "" : ": " + body));
                }
            } catch (Exception e) {
                Log.e(TAG, "sendSms error: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Network error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // ---- Heartbeat ----
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
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("deviceId", deviceId);
                body.put("sims", sims);           // "MVola,Orange Money,Airtel Money"
                body.put("battery", battery);
                body.put("smsReceived", smsReceived);
                body.put("smsSent", smsSent);
                body.put("ussdCheckEnabled", ussdCheckEnabled);
                body.put("networkType", networkType);
                body.put("signalLevel", signalLevel);
                body.put("timestamp", System.currentTimeMillis());

                byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(input); }

                int code = conn.getResponseCode();
                if (code == 200) {
                    String resp = readStream(conn.getInputStream());
                    callback.onSuccess(resp);
                } else {
                    callback.onError("HTTP " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "heartbeat error: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Network error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // ---- Test connexion ----
    public static void testConnection(String serverUrl, String apiKey, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/health");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                int code = conn.getResponseCode();
                if (code == 200) {
                    callback.onSuccess("ok");
                } else {
                    callback.onError("HTTP " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "test error: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Connection refused");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // ---- Récupérer stats depuis serveur ----
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
                if (code == 200) {
                    String resp = readStream(conn.getInputStream());
                    callback.onSuccess(resp);
                } else {
                    callback.onError("HTTP " + code);
                }
            } catch (Exception e) {
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // ---- Envoyer résultat retrait/USSD ----
    public static void sendRetraitResult(String serverUrl, String apiKey,
                                          String retraitId, boolean success,
                                          String response, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/api/retrait/result");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("retraitId", retraitId);
                body.put("success", success);
                body.put("smsMatcher", null);
                body.put("response", response);

                byte[] input = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(input); }

                int code = conn.getResponseCode();
                if (code == 200) {
                    callback.onSuccess("ok");
                } else {
                    callback.onError("HTTP " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "sendRetraitResult error: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // ---- Envoyer solde tena izy avy amin'''ny USSD check ----
    // Orange double portefeuille (APK master) : informe le backend du portefeuille actif.
    public static void setOrangeWallet(String serverUrl, String apiKey, boolean marchand, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/api/solde/orange-wallet");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setDoOutput(true);
                JSONObject body = new JSONObject();
                body.put("active", marchand ? "marchand" : "tsotra");
                byte[] input = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(input); }
                int code = conn.getResponseCode();
                if (code == 200) callback.onSuccess("ok");
                else callback.onError("HTTP " + code);
            } catch (Exception e) {
                Log.e(TAG, "setOrangeWallet error: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public static void sendSoldeCheck(String serverUrl, String apiKey,
                                       String operator, String ussdResponse,
                                       long timestamp, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/api/solde/check-result");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("operator", operator);
                body.put("ussdResponse", ussdResponse);
                body.put("timestamp", timestamp);

                byte[] input = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(input); }

                int code = conn.getResponseCode();
                if (code == 200) {
                    callback.onSuccess("ok");
                } else {
                    callback.onError("HTTP " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "sendSoldeCheck error: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public static void sendNumeroCheck(String serverUrl, String apiKey,
                                       String operator, String ussdResponse,
                                       long timestamp, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/api/numero/check-result");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("operator", operator);
                body.put("ussdResponse", ussdResponse);
                body.put("timestamp", timestamp);

                byte[] input = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(input); }

                int code = conn.getResponseCode();
                if (code == 200) {
                    callback.onSuccess("ok");
                } else {
                    callback.onError("HTTP " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "sendNumeroCheck error: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public static void sendNumeroSet(String serverUrl, String apiKey,
                                       String operator, String numero,
                                       Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/api/numero/set");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("operator", operator);
                body.put("numero", numero);
                

                byte[] input = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(input); }

                int code = conn.getResponseCode();
                if (code == 200) {
                    callback.onSuccess("ok");
                } else {
                    callback.onError("HTTP " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "sendNumeroSet error: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try {
            byte[] buf = new byte[1024];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            return sb.toString().trim();
        } catch (Exception e) { return ""; }
    }

    public static void sendBalance(String serverUrl, String apiKey,
                                   String operator, double montant, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                java.net.URL url = new java.net.URL(serverUrl + "/api/stats/balance");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                String body = "{\"operator\":\"" + operator + "\",\"montant\":" + montant + "}";
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                int code = conn.getResponseCode();
                if (code == 200) callback.onSuccess("ok");
                else callback.onError("HTTP " + code);
            } catch (Exception e) {
                Log.e(TAG, "sendBalance error: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // FIX: APK mandefa ny vokatry ny USSD retrait any amin'ny backend
    public static void sendUssdRetraitResult(String serverUrl, String apiKey,
                                   String retraitId, boolean success, String response, Callback callback) {
        sendUssdRetraitResult(serverUrl, apiKey, retraitId, success, response, false, callback);
    }

    public static void sendUssdRetraitResult(String serverUrl, String apiKey,
                                   String retraitId, boolean success, String response,
                                   boolean pinSubmitted, Callback callback) {
        sendUssdRetraitResult(serverUrl, apiKey, retraitId, success, response,
                pinSubmitted, "", callback);
    }

    public static void sendUssdRetraitResult(String serverUrl, String apiKey,
                                   String retraitId, boolean success, String response,
                                   boolean pinSubmitted, String motif, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                java.net.URL url = new java.net.URL(serverUrl + "/api/retrait/" + retraitId + "/ussd-result");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                // JSONObject echappe correctement les retours a la ligne : les menus
                // USSD en contiennent, et la construction manuelle produisait un JSON
                // invalide -> HTTP 400 -> resultat perdu et retrait fige.
                JSONObject payload = new JSONObject();
                payload.put("success", success);
                payload.put("response", response != null ? response : "");
                payload.put("pinSubmitted", pinSubmitted);
                payload.put("motif", motif == null ? "" : motif);
                conn.getOutputStream().write(payload.toString().getBytes(StandardCharsets.UTF_8));
                int code = conn.getResponseCode();
                if (code == 200) callback.onSuccess("ok");
                else callback.onError("HTTP " + code);
            } catch (Exception e) {
                Log.e(TAG, "sendUssdRetraitResult error: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public static void getServiceCommands(String serverUrl, String apiKey, String deviceId, Callback callback) {
        executor().submit(() -> {
            HttpURLConnection conn = null;
            try {
                java.net.URL url = new java.net.URL(serverUrl + "/api/service/commands?deviceId=" + deviceId);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                int code = conn.getResponseCode();
                if (code == 200) callback.onSuccess(readStream(conn.getInputStream()));
                else callback.onError("HTTP " + code);
            } catch (Exception e) {
                Log.e(TAG, "getServiceCommands error: " + e.getMessage());
                callback.onError(e.getMessage() != null ? e.getMessage() : "Error");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public static void shutdown() {
        ExecutorService e = EXECUTOR.get();
        if (e == null) return;
        e.shutdown();
        try { e.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
