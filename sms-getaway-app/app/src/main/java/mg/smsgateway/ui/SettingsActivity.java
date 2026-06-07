package mg.smsgateway.ui;

import android.os.Bundle;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
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
}
