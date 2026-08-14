package mg.smsgateway.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import mg.smsgateway.service.GatewayService;
import mg.smsgateway.service.SmsReceiver;
import mg.smsgateway.utils.Prefs;
import mg.smsgateway.utils.SimUtils;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST = 100;
    private WebView webView;
    private Prefs prefs;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver smsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!SmsReceiver.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
            try {
                JSONObject msg = new JSONObject();
                msg.put("id", System.currentTimeMillis() + "");
                msg.put("from",    intent.getStringExtra("from"));
                msg.put("message", intent.getStringExtra("message"));
                msg.put("sim",     intent.getStringExtra("sim"));
                msg.put("simSlot", intent.getIntExtra("simSlot", 0));
                msg.put("timestamp", new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(new java.util.Date()));
                msg.put("status", "pending");
                final String js = "if(window.addIncomingSms)addIncomingSms(" + msg + ");";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            } catch (Exception e) {}
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            String js = null;
            switch (action) {
                case "mg.smsgateway.SMS_SENT":
                    js = "STATE.stats.sent=(STATE.stats.sent||0)+1;STATE.stats.pending=Math.max(0,(STATE.stats.pending||1)-1);saveToStorage();render();";
                    break;
                case "mg.smsgateway.SMS_FAILED":
                    String failedFrom = intent.getStringExtra("from");
                    String failedSim  = intent.getStringExtra("sim");
                    String failedMsg  = intent.getStringExtra("message");
                    String safeFrom = failedFrom != null ? failedFrom.replace("'","") : "";
                    String safeSim  = failedSim  != null ? failedSim.replace("'","")  : "";
                    String safeMsg  = failedMsg  != null ? failedMsg.replace("'","") : "";
                    js = "STATE.stats.failed=(STATE.stats.failed||0)+1;"
                       + "var _f=STATE.messages.find(function(m){return m.from==='" + safeFrom + "'&&m.status==='pending';});"
                       + "if(_f){_f.status='failed';}"
                       + "else{STATE.messages.unshift({id:Date.now().toString(),from:'" + safeFrom + "',message:'" + safeMsg + "',sim:'" + safeSim + "',status:'failed',timestamp:new Date().toISOString()});}"
                       + "saveToStorage();render();";
                    break;
                case "mg.smsgateway.HEARTBEAT_OK":
                    js = "STATE.serverOk=true;render();";
                    break;
                case "mg.smsgateway.HEARTBEAT_FAIL":
                    js = "STATE.serverOk=false;render();";
                    break;
            }
            if (js != null) {
                final String finalJs = js;
                runOnUiThread(() -> webView.evaluateJavascript(finalJs, null));
            }
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        SimUtils.initSubscriptions(this);

        // Si la consultation de solde etait deja activee, s'assurer que
        // l'alarme est bien en place : une alarme systeme ne survit ni a un
        // arret force de l'application, ni a une mise a jour de l'APK.
        try {
            if (new mg.smsgateway.utils.Prefs(getApplicationContext()).getUssdCheckEnabled()) {
                mg.smsgateway.service.UssdBalanceScheduler.start(getApplicationContext());
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "reprise alarme solde: " + e.getMessage());
        }
        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!url.startsWith("file://") && !url.startsWith("about:")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
                return false;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                injectAndroidState();
            }
        });

        webView.loadUrl("file:///android_asset/pwa/index.html");
        requestPermissions();
    }


    private void injectAndroidState() {
        try {
            String js = "try{" +
                "STATE.serverUrl='" + esc(prefs.getServerUrl()) + "';" +
                "STATE.apiKey='" + esc(prefs.getApiKey()) + "';" +
                "STATE.deviceId='" + esc(prefs.getDeviceId()) + "';" +
                "STATE.serviceRunning=" + GatewayService.running.get() + ";" +
                "STATE.ussdPinService=" +
                    mg.smsgateway.service.UssdAccessibilityService.isEnabled(MainActivity.this) + ";" +
                "STATE.overlayOk=" +
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                     || android.provider.Settings.canDrawOverlays(MainActivity.this)) + ";" +
                /* stats: source unique = getLocalStats() via syncStatsFromSQLite() */
                "STATE.simCounts=[" + prefs.getSimCount(0) + "," +
                    prefs.getSimCount(1) + "," + prefs.getSimCount(2) + "];" +
                buildSimStatusJs() +
                "localStorage.setItem('serverUrl','" + esc(prefs.getServerUrl()) + "');" +
                "localStorage.setItem('apiKey','" + esc(prefs.getApiKey()) + "');" +
                "localStorage.setItem('deviceId','" + esc(prefs.getDeviceId()) + "');" +
                "render();" +
                "}catch(e){}";
            webView.evaluateJavascript(js, null);
        } catch (Exception e) {}
    }

    class AndroidBridge {
        @JavascriptInterface
        public void startService(String url, String key) {
            prefs.setServerUrl(url);
            prefs.setApiKey(key);
            Intent i = new Intent(MainActivity.this, GatewayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else MainActivity.this.startService(i);
        }
        @JavascriptInterface
        public void stopService() {
            Intent i = new Intent(MainActivity.this, GatewayService.class);
            i.setAction("STOP");
            MainActivity.this.startService(i);
        }
        @JavascriptInterface
        public void saveSettings(String url, String key) {
            prefs.setServerUrl(url);
            prefs.setApiKey(key);
        }
        @JavascriptInterface
        public void setUssdBalance(String operator, String code) {
            prefs.setUssdBalance(operator, code);
        }
        @JavascriptInterface
        public String getUssdBalance(String operator) {
            return prefs.getUssdBalance(operator);
        }

        // ---- Orange double portefeuille (APK master) ----
        // Le code solde marchand et le toggle marchand/tsotra etaient jusqu'ici
        // accessibles uniquement depuis l'ancien ecran natif SettingsActivity,
        // qui n'est plus ouvert par l'application. On les expose donc au PWA.
        @JavascriptInterface
        public void setUssdBalanceMarchand(String code) {
            prefs.setUssdBalanceMarchand(code);
        }
        @JavascriptInterface
        public String getUssdBalanceMarchand() {
            return prefs.getUssdBalanceMarchand();
        }
        @JavascriptInterface
        public boolean isOrangeMarchand() {
            return prefs.isOrangeMarchand();
        }
        /**
         * Bascule le portefeuille Orange (ON = marchand, OFF = tsotra).
         * L'APK est le maitre : on enregistre localement PUIS on previent le
         * backend pour que retrait et depot utilisent le meme portefeuille.
         */
        @JavascriptInterface
        public void setOrangeMarchand(boolean marchand) {
            prefs.setOrangeMarchand(marchand);
            String url = prefs.getServerUrl();
            String key = prefs.getApiKey();
            if (url != null && !url.isEmpty()) {
                mg.smsgateway.network.ApiClient.setOrangeWallet(url, key, marchand,
                    new mg.smsgateway.network.ApiClient.Callback() {
                        @Override public void onSuccess(String r) {
                            android.util.Log.d("AndroidBridge", "wallet orange sync: " + r);
                        }
                        @Override public void onError(String e) {
                            android.util.Log.e("AndroidBridge", "wallet orange sync KO: " + e);
                        }
                    });
            }
        }
        /**
         * true si le service d'accessibilite (saisie du PIN USSD) est actif.
         * Sans lui, les retraits Orange restent bloques a l'invite "code secret".
         */
        @JavascriptInterface
        public boolean isUssdPinServiceEnabled() {
            return mg.smsgateway.service.UssdAccessibilityService.isEnabled(MainActivity.this);
        }

        /** true si l'app peut s'afficher par-dessus les autres apps (Android 10+). */
        @JavascriptInterface
        public boolean isOverlayAllowed() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
            try { return android.provider.Settings.canDrawOverlays(MainActivity.this); }
            catch (Exception e) { return false; }
        }

        /** Ouvre le reglage "Afficher par-dessus les autres applications". */
        @JavascriptInterface
        public void openOverlaySettings() {
            MainActivity.this.runOnUiThread(() -> {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + MainActivity.this.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    MainActivity.this.startActivity(i);
                } catch (Exception e) {
                    try {
                        MainActivity.this.startActivity(
                            new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                    } catch (Exception e2) {
                        android.widget.Toast.makeText(MainActivity.this,
                            "Reglages > Applications > Acces special > Afficher par-dessus",
                            android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        /** Ouvre les reglages d'accessibilite pour que l'utilisateur active le service. */
        @JavascriptInterface
        public void openAccessibilitySettings() {
            MainActivity.this.runOnUiThread(() -> {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    MainActivity.this.startActivity(i);
                } catch (Exception e) {
                    try {
                        MainActivity.this.startActivity(
                            new Intent(android.provider.Settings.ACTION_SETTINGS));
                    } catch (Exception e2) {
                        android.widget.Toast.makeText(MainActivity.this,
                            "Ouvrez Reglages > Accessibilite > MATULMADA",
                            android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean isServiceRunning() {
            return GatewayService.running.get();
        }
        @JavascriptInterface
        public void resetStats() {
            prefs.resetStats();
            // Clear SQLite queue koa
            mg.smsgateway.utils.SmsQueue.getInstance(
                MainActivity.this.getApplicationContext()).clearAll();
        }
        @JavascriptInterface
        public String getLocalStats() {
            try {
                mg.smsgateway.utils.SmsQueue queue =
                    mg.smsgateway.utils.SmsQueue.getInstance(
                        MainActivity.this.getApplicationContext());
                java.util.List<mg.smsgateway.model.SmsMessage> list =
                    queue.getRecentMessages(1000);
                int received = list.size();  // total SMS reçus (tous statuts confondus)
                int sent = 0, pending = 0, failed = 0;
                for (mg.smsgateway.model.SmsMessage sms : list) {
                    String st = sms.getStatus();
                    if ("sent".equals(st)) sent++;
                    else if ("failed".equals(st)) failed++;
                    else pending++;  // pending na received na bange → "en attente"
                }
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("received", received);
                o.put("sent", sent);
                o.put("pending", pending);
                o.put("failed", failed);
                return o.toString();
            } catch (Exception e) {
                return "{\"received\":0,\"sent\":0,\"pending\":0,\"failed\":0}";
            }
        }
        @JavascriptInterface
        public String getLocalSms() {
            try {
                mg.smsgateway.utils.SmsQueue queue =
                    mg.smsgateway.utils.SmsQueue.getInstance(
                        MainActivity.this.getApplicationContext());
                java.util.List<mg.smsgateway.model.SmsMessage> list =
                    queue.getRecentMessages(200);
                org.json.JSONArray arr = new org.json.JSONArray();
                for (mg.smsgateway.model.SmsMessage sms : list) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("id",        sms.getId());
                    o.put("from",      sms.getFrom() != null ? sms.getFrom() : "");
                    o.put("message",   sms.getMessage() != null ? sms.getMessage() : "");
                    o.put("sim",       sms.getSim() != null ? sms.getSim() : "");
                    o.put("simSlot",   sms.getSimSlot());
                    o.put("timestamp", sms.getTimestamp() != null ? sms.getTimestamp() : "");
                    o.put("status",    sms.getStatus() != null ? sms.getStatus() : "pending");
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }
        @JavascriptInterface
        public void replySms(String to, String message) {
            try {
                android.telephony.SmsManager sm = android.telephony.SmsManager.getDefault();
                java.util.ArrayList<String> parts = sm.divideMessage(message);
                sm.sendMultipartTextMessage(to, null, parts, null, null);
            } catch (Exception e) {
                android.util.Log.e("AndroidBridge", "replySms error: " + e.getMessage());
            }
        }
        @JavascriptInterface
        public void deleteSms(String id) {
            try {
                mg.smsgateway.utils.SmsQueue.getInstance(
                    MainActivity.this.getApplicationContext()).deleteSms(id);
            } catch (Exception e) {
                android.util.Log.e("AndroidBridge", "deleteSms error: " + e.getMessage());
            }
        }
        @JavascriptInterface
        public void clearAllSms() {
            try {
                mg.smsgateway.utils.SmsQueue.getInstance(
                    MainActivity.this.getApplicationContext()).clearAll();
            } catch (Exception e) {
                android.util.Log.e("AndroidBridge", "clearAllSms error: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void setUssdCheckEnabled(boolean enabled) {
            try {
                android.content.Context ctx = MainActivity.this.getApplicationContext();
                mg.smsgateway.utils.Prefs p = new mg.smsgateway.utils.Prefs(ctx);
                p.setUssdCheckEnabled(enabled);

                // ----------------------------------------------------------
                // BUG CORRIGE : enregistrer la preference ne suffisait pas.
                // ----------------------------------------------------------
                // La consultation de solde repose sur une alarme systeme, posee
                // par UssdBalanceScheduler.start(). Or start() n'etait appele
                // que depuis l'ancien ecran SettingsActivity et au demarrage du
                // telephone. En reglant la consultation depuis l'interface web
                // (le cas normal), l'option passait bien a "activee" mais
                // AUCUNE alarme n'etait posee : le solde n'etait jamais relu,
                // et l'ecran d'administration affichait indefiniment la
                // derniere valeur connue.
                // ----------------------------------------------------------
                if (enabled) {
                    mg.smsgateway.service.UssdBalanceScheduler.start(ctx);
                    android.util.Log.d("AndroidBridge", "consultation de solde : alarme posee");
                } else {
                    mg.smsgateway.service.UssdBalanceScheduler.stop(ctx);
                    android.util.Log.d("AndroidBridge", "consultation de solde : alarme retiree");
                }
            } catch (Exception e) {
                android.util.Log.e("AndroidBridge", "setUssdCheckEnabled error: " + e.getMessage());
            }
        }

        /**
         * Relance immediatement une consultation de solde, sans attendre
         * l'alarme. Permet de verifier la configuration depuis l'interface.
         */
        @JavascriptInterface
        public void checkSoldeNow(String operator) {
            try {
                android.content.Context ctx = MainActivity.this.getApplicationContext();
                mg.smsgateway.service.UssdBalanceScheduler.apresMouvement(ctx, operator);
                android.util.Log.d("AndroidBridge", "consultation immediate demandee: " + operator);
            } catch (Exception e) {
                android.util.Log.e("AndroidBridge", "checkSoldeNow: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public boolean getUssdCheckEnabled() {
            try {
                mg.smsgateway.utils.Prefs p = new mg.smsgateway.utils.Prefs(MainActivity.this.getApplicationContext());
                return p.getUssdCheckEnabled();
            } catch (Exception e) {
                return false;
            }
        }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n");
    }

    private void requestPermissions() {
        java.util.ArrayList<String> perms = new java.util.ArrayList<>();
        String[] needed = {
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        for (String p : needed)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                perms.add(p);
        if (!perms.isEmpty())
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERMISSION_REQUEST);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction("mg.smsgateway.SMS_SENT");
            f.addAction("mg.smsgateway.SMS_FAILED");
            f.addAction("mg.smsgateway.HEARTBEAT_OK");
            f.addAction("mg.smsgateway.HEARTBEAT_FAIL");
            enregistrerReceiver(smsReceiver, new IntentFilter(SmsReceiver.SMS_RECEIVED_ACTION));
            enregistrerReceiver(statusReceiver, f);
            receiverRegistered = true;
        }
        SimUtils.initSubscriptions(this);
        webView.post(this::injectAndroidState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            try { unregisterReceiver(smsReceiver); } catch (Exception ignored) {}
            try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
    private String buildSimStatusJs() {
        try {
            org.json.JSONArray arr = mg.smsgateway.utils.SimUtils.getSimStatuses(getApplicationContext());
            // Ampidiro ny JSON array any amin'ny JS dia izy no manapa-kevitra
            return "var _sims=" + arr.toString() + ";" +
                "_sims.forEach(function(s,i){" +
                "  var el=document.getElementById('sim-status-'+i);" +
                "  if(!el) return;" +
                "  if(s.active){" +
                "    el.textContent='● Actif';" +
                "    el.style.background='#ECFDF5';" +
                "    el.style.color='#059669';" +
                "  } else {" +
                "    el.textContent='● Inactif';" +
                "    el.style.background='#FEF2F2';" +
                "    el.style.color='#DC2626';" +
                "  }" +
                "});";
        } catch (Exception e) { return ""; }
    }


    /* ============================================================
     * ENREGISTREMENT DES RECEIVERS — Android 13 et au-dela.
     * ------------------------------------------------------------
     * Depuis Android 13 (API 33), tout receiver enregistre a l'execution
     * DOIT declarer s'il accepte les diffusions venant d'autres
     * applications : RECEIVER_EXPORTED ou RECEIVER_NOT_EXPORTED.
     * Sans ce drapeau, et avec targetSdk >= 33, le systeme leve
     * SecurityException et l'application se ferme immediatement.
     * C'est ce qui provoquait "SMS Gateway s'arrete systematiquement"
     * sur les telephones recents (Motorola G Power 2022 et autres).
     *
     * Nos diffusions sont internes a l'application : NOT_EXPORTED est
     * donc le bon choix, et c'est aussi le plus sur — aucune autre
     * application ne peut nous envoyer de faux evenements.
     * ============================================================ */
    private void enregistrerReceiver(android.content.BroadcastReceiver r,
                                     android.content.IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(r, f);
        }
    }
}