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
                    js = "STATE.stats.failed=(STATE.stats.failed||0)+1;saveToStorage();render();";
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
                "STATE.stats={received:" + prefs.getSmsReceived() +
                    ",sent:" + prefs.getSmsSent() +
                    ",pending:" + prefs.getSmsPending() +
                    ",failed:" + prefs.getSmsFailed() + "};" +
                "STATE.simCounts=[" + prefs.getSimCount(0) + "," +
                    prefs.getSimCount(1) + "," + prefs.getSimCount(2) + "];" +
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
            else startService(i);
        }
        @JavascriptInterface
        public void stopService() {
            Intent i = new Intent(MainActivity.this, GatewayService.class);
            i.setAction("STOP");
            startService(i);
        }
        @JavascriptInterface
        public void saveSettings(String url, String key) {
            prefs.setServerUrl(url);
            prefs.setApiKey(key);
        }
        @JavascriptInterface
        public boolean isServiceRunning() {
            return GatewayService.running.get();
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
            Manifest.permission.READ_PHONE_STATE
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
            registerReceiver(smsReceiver, new IntentFilter(SmsReceiver.SMS_RECEIVED_ACTION));
            registerReceiver(statusReceiver, f);
            receiverRegistered = true;
        }
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
}
