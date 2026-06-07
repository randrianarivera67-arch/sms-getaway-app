package mg.smsgateway.ui;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import mg.smsgateway.R;
import mg.smsgateway.service.GatewayService;
import mg.smsgateway.service.SmsReceiver;
import mg.smsgateway.utils.Prefs;
import mg.smsgateway.utils.SimUtils;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST = 100;
    private Prefs prefs;

    // UI
    private Button  btnToggle;
    private TextView tvStatus, tvServerUrl, tvSmsReceived, tvSmsSent;
    private TextView tvSmsPending, tvSmsFailed, tvServerStatus, tvLastSms;
    private TextView tvSim0Name, tvSim1Name, tvSim2Name;
    private TextView tvSim0Count, tvSim1Count, tvSim2Count;
    private TextView tvNotifBadge;
    private ImageView ivStatusIcon, ivServerIcon;
    private View     dotStatus;
    private View     cardStatus, cardGrid, cardSim, cardLast;

    private boolean receiverRegistered = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver uiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case SmsReceiver.SMS_RECEIVED_ACTION:
                    String from    = intent.getStringExtra("from");
                    String message = intent.getStringExtra("message");
                    String sim     = intent.getStringExtra("sim");
                    int simSlot    = intent.getIntExtra("simSlot", 0);
                    String preview = (message != null && message.length() > 80)
                        ? message.substring(0, 80) + "…" : message;
                    tvLastSms.setText("[" + sim + "] " + from + "\n" + preview);
                    animateCountUpdate(tvSmsReceived, prefs.getSmsReceived());
                    animateCountUpdate(getSimCountView(simSlot), prefs.getSimCount(simSlot));
                    updateNotifBadge();
                    pulseCard(cardLast);
                    break;
                case "mg.smsgateway.SMS_SENT":
                    animateCountUpdate(tvSmsSent, prefs.getSmsSent());
                    refreshPendingFailed();
                    break;
                case "mg.smsgateway.SMS_FAILED":
                    refreshPendingFailed();
                    break;
                case "mg.smsgateway.HEARTBEAT_OK":
                    setServerConnected(true);
                    break;
                case "mg.smsgateway.HEARTBEAT_FAIL":
                    setServerConnected(false);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        bindViews();
        setupClickEffects();
        setupNavigation();
        populateStats();
        updateServiceStatus();
        updateNotifBadge();
        requestPermissions();
        animateEntrance();
    }

    private void bindViews() {
        btnToggle       = findViewById(R.id.btn_toggle_service);
        tvStatus        = findViewById(R.id.tv_status);
        tvServerUrl     = findViewById(R.id.tv_server_url);
        tvSmsReceived   = findViewById(R.id.tv_sms_received);
        tvSmsSent       = findViewById(R.id.tv_sms_sent);
        tvSmsPending    = findViewById(R.id.tv_sms_pending);
        tvSmsFailed     = findViewById(R.id.tv_sms_failed);
        tvServerStatus  = findViewById(R.id.tv_server_status);
        tvLastSms       = findViewById(R.id.tv_last_sms);
        ivStatusIcon    = findViewById(R.id.iv_status_icon);
        ivServerIcon    = findViewById(R.id.iv_server_icon);
        dotStatus       = findViewById(R.id.dot_status);
        tvNotifBadge    = findViewById(R.id.tv_notif_badge);
        tvSim0Name      = findViewById(R.id.tv_sim0_name);
        tvSim1Name      = findViewById(R.id.tv_sim1_name);
        tvSim2Name      = findViewById(R.id.tv_sim2_name);
        tvSim0Count     = findViewById(R.id.tv_sim0_count);
        tvSim1Count     = findViewById(R.id.tv_sim1_count);
        tvSim2Count     = findViewById(R.id.tv_sim2_count);
        cardStatus      = findViewById(R.id.card_status);
        cardGrid        = findViewById(R.id.card_grid);
        cardSim         = findViewById(R.id.card_sim);
        cardLast        = findViewById(R.id.card_last);

        tvSim0Name.setText(SimUtils.getSimName(0));
        tvSim1Name.setText(SimUtils.getSimName(1));
        tvSim2Name.setText(SimUtils.getSimName(2));
    }

    private void setupClickEffects() {
        // Ripple + scale effect sur tous les boutons
        for (int id : new int[]{R.id.btn_toggle_service, R.id.btn_settings,
                                  R.id.btn_inbox, R.id.btn_stats, R.id.btn_notif}) {
            View v = findViewById(id);
            if (v == null) continue;
            v.setOnTouchListener((view, event) -> {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.93f).scaleY(0.93f)
                            .setDuration(80).setInterpolator(new DecelerateInterpolator()).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(200).setInterpolator(new OvershootInterpolator(2f)).start();
                        break;
                }
                return false;
            });
        }
    }

    private void setupNavigation() {
        btnToggle.setOnClickListener(v -> toggleService());

        findViewById(R.id.btn_settings).setOnClickListener(v ->
            startActivityWithAnim(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.btn_inbox).setOnClickListener(v -> {
            prefs.clearNotifCount();
            updateNotifBadge();
            startActivityWithAnim(new Intent(this, InboxActivity.class));
        });

        findViewById(R.id.btn_stats).setOnClickListener(v ->
            startActivityWithAnim(new Intent(this, StatsActivity.class)));

        View btnNotif = findViewById(R.id.btn_notif);
        if (btnNotif != null) {
            btnNotif.setOnClickListener(v -> {
                prefs.clearNotifCount();
                updateNotifBadge();
                startActivityWithAnim(new Intent(this, InboxActivity.class));
            });
        }
    }

    private void startActivityWithAnim(Intent intent) {
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void populateStats() {
        tvSmsReceived.setText(String.valueOf(prefs.getSmsReceived()));
        tvSmsSent.setText(String.valueOf(prefs.getSmsSent()));
        tvSmsPending.setText(String.valueOf(prefs.getSmsPending()));
        tvSmsFailed.setText(String.valueOf(prefs.getSmsFailed()));
        tvSim0Count.setText(String.valueOf(prefs.getSimCount(0)));
        tvSim1Count.setText(String.valueOf(prefs.getSimCount(1)));
        tvSim2Count.setText(String.valueOf(prefs.getSimCount(2)));
        String url = prefs.getServerUrl();
        tvServerUrl.setText(url.isEmpty() ? "Non configuré" : url);
    }

    private void updateNotifBadge() {
        int count = prefs.getNotifCount();
        if (tvNotifBadge != null) {
            if (count > 0) {
                tvNotifBadge.setVisibility(View.VISIBLE);
                tvNotifBadge.setText(count > 99 ? "99+" : String.valueOf(count));
            } else {
                tvNotifBadge.setVisibility(View.GONE);
            }
        }
    }

    private void refreshPendingFailed() {
        tvSmsPending.setText(String.valueOf(prefs.getSmsPending()));
        tvSmsFailed.setText(String.valueOf(prefs.getSmsFailed()));
    }

    private void toggleService() {
        if (GatewayService.running.get()) {
            Intent intent = new Intent(this, GatewayService.class);
            intent.setAction("STOP");
            startService(intent);
            uiHandler.postDelayed(this::updateServiceStatus, 300);
        } else {
            if (prefs.getServerUrl().isEmpty()) {
                Toast.makeText(this, "Configurez l'URL du serveur d'abord", Toast.LENGTH_SHORT).show();
                startActivityWithAnim(new Intent(this, SettingsActivity.class));
                return;
            }
            if (prefs.getApiKey().isEmpty()) {
                Toast.makeText(this, "Configurez l'API Key d'abord", Toast.LENGTH_SHORT).show();
                startActivityWithAnim(new Intent(this, SettingsActivity.class));
                return;
            }
            Intent intent = new Intent(this, GatewayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            uiHandler.postDelayed(this::updateServiceStatus, 300);
        }
    }

    private void updateServiceStatus() {
        boolean active = GatewayService.running.get();
        if (active) {
            tvStatus.setText("Service actif");
            tvStatus.setTextColor(getColorCompat(R.color.success));
            ivStatusIcon.setImageResource(R.drawable.ic_check);
            btnToggle.setText("Arrêter");
            btnToggle.setBackgroundResource(R.drawable.btn_danger);
            if (dotStatus != null) dotStatus.setBackgroundResource(R.drawable.dot_green);
            pulseCard(cardStatus);
        } else {
            tvStatus.setText("Service arrêté");
            tvStatus.setTextColor(getColorCompat(R.color.error));
            ivStatusIcon.setImageResource(R.drawable.ic_error);
            btnToggle.setText("Démarrer");
            btnToggle.setBackgroundResource(R.drawable.btn_primary);
            if (dotStatus != null) dotStatus.setBackgroundResource(R.drawable.dot_red);
        }
    }

    private void setServerConnected(boolean ok) {
        if (ok) {
            tvServerStatus.setText("Connecté");
            tvServerStatus.setTextColor(getColorCompat(R.color.success));
            ivServerIcon.setImageResource(R.drawable.ic_check);
        } else {
            tvServerStatus.setText("Déconnecté");
            tvServerStatus.setTextColor(getColorCompat(R.color.error));
            ivServerIcon.setImageResource(R.drawable.ic_error);
        }
    }

    private TextView getSimCountView(int slot) {
        switch (slot) {
            case 0: return tvSim0Count;
            case 1: return tvSim1Count;
            case 2: return tvSim2Count;
            default: return tvSim0Count;
        }
    }

    // ---- Animations ----
    private void animateEntrance() {
        View[] cards = {cardStatus, cardGrid, cardSim, cardLast};
        for (int i = 0; i < cards.length; i++) {
            if (cards[i] == null) continue;
            cards[i].setAlpha(0f);
            cards[i].setTranslationY(40f);
            cards[i].animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(i * 80L)
                .setDuration(350)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        }
    }

    private void animateCountUpdate(TextView tv, int newValue) {
        if (tv == null) return;
        try {
            int oldVal = Integer.parseInt(tv.getText().toString());
            ValueAnimator anim = ValueAnimator.ofInt(oldVal, newValue);
            anim.setDuration(500);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> tv.setText(String.valueOf((int) a.getAnimatedValue())));
            anim.start();
        } catch (NumberFormatException e) {
            tv.setText(String.valueOf(newValue));
        }
        // Scale pulse
        tv.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150)
            .withEndAction(() -> tv.animate().scaleX(1f).scaleY(1f).setDuration(150).start())
            .start();
    }

    private void pulseCard(View card) {
        if (card == null) return;
        card.animate().scaleX(1.02f).scaleY(1.02f).setDuration(100)
            .withEndAction(() ->
                card.animate().scaleX(1f).scaleY(1f).setDuration(200)
                    .setInterpolator(new OvershootInterpolator()).start())
            .start();
    }

    private int getColorCompat(int colorResId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getColor(colorResId);
        }
        return getResources().getColor(colorResId);
    }

    // ---- Permissions ----
    private void requestPermissions() {
        java.util.ArrayList<String> perms = new java.util.ArrayList<>();
        String[] needed = {
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                perms.add(p);
        }
        if (!perms.isEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(SmsReceiver.SMS_RECEIVED_ACTION);
            filter.addAction("mg.smsgateway.SMS_SENT");
            filter.addAction("mg.smsgateway.SMS_FAILED");
            filter.addAction("mg.smsgateway.HEARTBEAT_OK");
            filter.addAction("mg.smsgateway.HEARTBEAT_FAIL");
            registerReceiver(uiReceiver, filter);
            receiverRegistered = true;
        }
        updateServiceStatus();
        populateStats();
        updateNotifBadge();
        String url = prefs.getServerUrl();
        tvServerUrl.setText(url.isEmpty() ? "Non configuré" : url);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            try { unregisterReceiver(uiReceiver); } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
    }
}
