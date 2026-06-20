package mg.smsgateway.ui;

import android.os.Bundle;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.os.Build;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import mg.smsgateway.R;
import mg.smsgateway.network.ApiClient;
import mg.smsgateway.utils.Prefs;

public class SettingsActivity extends AppCompatActivity {

    private Prefs    prefs;
    private EditText etServerUrl, etApiKey;
    private TextView tvDeviceId;
    private Switch   switchAutoStart;
    private Button   btnTest, btnSave;
    private boolean  apiKeyVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new Prefs(this);

        etServerUrl    = findViewById(R.id.et_server_url);
        etApiKey       = findViewById(R.id.et_api_key);
        tvDeviceId     = findViewById(R.id.tv_device_id);
        switchAutoStart= findViewById(R.id.switch_auto_start);
        btnTest        = findViewById(R.id.btn_test);
        btnSave        = findViewById(R.id.btn_save);

        // USSD balance EditTexts
        EditText etUssdOrange = findViewById(R.id.et_ussd_orange);
        EditText etUssdMvola  = findViewById(R.id.et_ussd_mvola);
        EditText etUssdAirtel = findViewById(R.id.et_ussd_airtel);
        if (etUssdOrange != null) etUssdOrange.setText(prefs.getUssdBalance("orange"));
        if (etUssdMvola  != null) etUssdMvola.setText(prefs.getUssdBalance("mvola"));
        if (etUssdAirtel != null) etUssdAirtel.setText(prefs.getUssdBalance("airtel"));

        Button btnSaveUssd = findViewById(R.id.btn_save_ussd);
        if (btnSaveUssd != null) {
            btnSaveUssd.setOnClickListener(v -> {
                prefs.setUssdBalance("orange", etUssdOrange.getText().toString().trim());
                prefs.setUssdBalance("mvola",  etUssdMvola.getText().toString().trim());
                prefs.setUssdBalance("airtel", etUssdAirtel.getText().toString().trim());
                Toast.makeText(this, "Codes USSD enregistrés", Toast.LENGTH_SHORT).show();
            });

        // ───── USSD Numéro Gateway ─────
        EditText etNumOrange = findViewById(R.id.et_numero_orange);
        EditText etNumMvola  = findViewById(R.id.et_numero_mvola);
        EditText etNumAirtel = findViewById(R.id.et_numero_airtel);
        if (etNumOrange != null) etNumOrange.setText(prefs.getUssdNumero("orange"));
        if (etNumMvola  != null) etNumMvola.setText(prefs.getUssdNumero("mvola"));
        if (etNumAirtel != null) etNumAirtel.setText(prefs.getUssdNumero("airtel"));

        Button btnSaveNumero = findViewById(R.id.btn_save_numero);
        if (btnSaveNumero != null) {
            btnSaveNumero.setOnClickListener(v -> {
                prefs.setUssdNumero("orange", etNumOrange.getText().toString().trim());
                prefs.setUssdNumero("mvola",  etNumMvola.getText().toString().trim());
                prefs.setUssdNumero("airtel", etNumAirtel.getText().toString().trim());
                Toast.makeText(this, "Codes numéro enregistrés", Toast.LENGTH_SHORT).show();
            });
        }

        Button btnDetectNumero = findViewById(R.id.btn_detect_numero);
        if (btnDetectNumero != null) {
            btnDetectNumero.setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    Toast.makeText(this, "Android 8+ requis", Toast.LENGTH_SHORT).show();
                    return;
                }
                detectNumero("orange", prefs.getUssdNumero("orange"));
                detectNumero("mvola",  prefs.getUssdNumero("mvola"));
                detectNumero("airtel", prefs.getUssdNumero("airtel"));
                Toast.makeText(this, "Détection en cours…", Toast.LENGTH_SHORT).show();
            });
        }
        }

        // Toggle vérification solde automatique via USSD
        Switch switchUssdCheck = findViewById(R.id.switch_ussd_check);
        if (switchUssdCheck != null) {
            switchUssdCheck.setChecked(prefs.getUssdCheckEnabled());
            switchUssdCheck.setOnCheckedChangeListener((b, checked) -> {
                prefs.setUssdCheckEnabled(checked);
                if (checked) {
                    mg.smsgateway.service.UssdBalanceScheduler.start(getApplicationContext());
                    Toast.makeText(this, "Vérification solde automatique activée",
                        Toast.LENGTH_SHORT).show();
                } else {
                    mg.smsgateway.service.UssdBalanceScheduler.stop(getApplicationContext());
                    Toast.makeText(this, "Vérification solde automatique désactivée",
                        Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Pré-remplir
        etServerUrl.setText(prefs.getServerUrl());
        etApiKey.setText(prefs.getApiKey());
        tvDeviceId.setText(prefs.getDeviceId());
        if (switchAutoStart != null)
            switchAutoStart.setChecked(prefs.getAutoStart());

        // Retour
        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Toggle visibilité API Key
        View btnToggleKey = findViewById(R.id.btn_toggle_key);
        if (btnToggleKey != null) {
            btnToggleKey.setOnClickListener(v -> {
                apiKeyVisible = !apiKeyVisible;
                etApiKey.setTransformationMethod(
                    apiKeyVisible ? null : PasswordTransformationMethod.getInstance());
                etApiKey.setSelection(etApiKey.getText().length());
            });
        }

        // Auto-start
        if (switchAutoStart != null) {
            switchAutoStart.setOnCheckedChangeListener((b, checked) ->
                prefs.setAutoStart(checked));
        }

        // Test connexion
        btnTest.setOnClickListener(v -> {
            String url = etServerUrl.getText().toString().trim();
            String key = etApiKey.getText().toString().trim();
            if (url.isEmpty()) {
                etServerUrl.setError("URL obligatoire");
                return;
            }
            if (key.isEmpty()) {
                etApiKey.setError("API Key obligatoire");
                return;
            }
            btnTest.setText("Test en cours...");
            btnTest.setEnabled(false);
            String finalUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            ApiClient.testConnection(finalUrl, key, new ApiClient.Callback() {
                @Override
                public void onSuccess(String id) {
                    runOnUiThread(() -> {
                        btnTest.setText("Tester la connexion");
                        btnTest.setEnabled(true);
                        Toast.makeText(SettingsActivity.this,
                            "✓ Connexion réussie !", Toast.LENGTH_SHORT).show();
                        btnTest.setBackgroundResource(R.drawable.btn_success);
                    });
                }
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        btnTest.setText("Tester la connexion");
                        btnTest.setEnabled(true);
                        Toast.makeText(SettingsActivity.this,
                            "✗ Échec: " + error, Toast.LENGTH_LONG).show();
                        btnTest.setBackgroundResource(R.drawable.btn_danger);
                    });
                }
            });
        });

        // Sauvegarder
        btnSave.setOnClickListener(v -> {
            String url = etServerUrl.getText().toString().trim();
            String key = etApiKey.getText().toString().trim();
            if (url.isEmpty()) { etServerUrl.setError("URL obligatoire"); return; }
            if (key.isEmpty()) { etApiKey.setError("API Key obligatoire"); return; }
            if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            prefs.setServerUrl(url);
            prefs.setApiKey(key);
            Toast.makeText(this, "✓ Paramètres sauvegardés", Toast.LENGTH_SHORT).show();
            // Animate button
            btnSave.animate().scaleX(1.05f).scaleY(1.05f).setDuration(100)
                .withEndAction(() -> btnSave.animate().scaleX(1f).scaleY(1f).setDuration(150).start())
                .start();
            finish();
        });
    }

    @androidx.annotation.RequiresApi(api = android.os.Build.VERSION_CODES.O)
    private void detectNumero(String operator, String code) {
        if (code == null || code.trim().isEmpty()) return;
        mg.smsgateway.service.UssdEngine.sendUssd(getApplicationContext(),
            "numero_" + operator, code.trim(), operator,
            (id, success, response) -> {
                if (!success) { android.util.Log.e("SettingsActivity", "detectNumero echec " + operator + ": " + response); return; }
                String serverUrl = prefs.getServerUrl();
                String apiKey    = prefs.getApiKey();
                if (serverUrl == null || serverUrl.isEmpty()) return;
                mg.smsgateway.network.ApiClient.sendNumeroCheck(serverUrl, apiKey,
                    operator, response, System.currentTimeMillis(),
                    new mg.smsgateway.network.ApiClient.Callback() {
                        public void onSuccess(String okId) { android.util.Log.d("SettingsActivity", "numero ok " + operator); }
                        public void onError(String err) { android.util.Log.e("SettingsActivity", "numero post err " + err); }
                    });
            });
    }
}
