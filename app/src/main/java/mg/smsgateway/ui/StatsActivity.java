package mg.smsgateway.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import mg.smsgateway.R;
import mg.smsgateway.utils.Prefs;
import mg.smsgateway.utils.SimUtils;
import mg.smsgateway.utils.SmsQueue;

public class StatsActivity extends AppCompatActivity {

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        prefs = new Prefs(this);

        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        loadStats();
        android.widget.Button btnReset=findViewById(R.id.btn_reset_stats);
        if(btnReset!=null)btnReset.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Réinitialiser").setMessage("Supprimer toutes les statistiques ?").setPositiveButton("Oui",(d,w)->{prefs.resetStats();
                        SmsQueue.getInstance(StatsActivity.this).clearAll();
                        loadStats();
                    }).setNegativeButton("Annuler",null).show());
    }

    private void loadStats() {
        SmsQueue queue = SmsQueue.getInstance(this);

        int received = prefs.getSmsReceived();
        int sent     = prefs.getSmsSent();
        int pending  = queue.getPendingCount();
        int failed   = queue.getFailedCount();
        int total    = received;

        // Totaux
        setText(R.id.tv_stat_received, String.valueOf(received));
        setText(R.id.tv_stat_sent,     String.valueOf(sent));
        setText(R.id.tv_stat_pending,  String.valueOf(pending));
        setText(R.id.tv_stat_failed,   String.valueOf(failed));

        // Taux de succès
        float successRate = total > 0 ? (sent / (float) total) * 100f : 0f;
        setText(R.id.tv_success_rate, String.format("%.1f%%", successRate));

        ProgressBar pbSuccess = findViewById(R.id.pb_success);
        if (pbSuccess != null) pbSuccess.setProgress((int) successRate);

        // Répartition par SIM
        for (int slot = 0; slot < 3; slot++) {
            int count = prefs.getSimCount(slot);
            float pct = total > 0 ? (count / (float) total) * 100f : 0f;
            String label = SimUtils.getSimName(slot) + " — " + count
                + " SMS (" + String.format("%.0f%%", pct) + ")";

            switch (slot) {
                case 0:
                    setText(R.id.tv_sim0_stat, label);
                    setProgress(R.id.pb_sim0, (int) pct, SimUtils.getSimColor(0));
                    break;
                case 1:
                    setText(R.id.tv_sim1_stat, label);
                    setProgress(R.id.pb_sim1, (int) pct, SimUtils.getSimColor(1));
                    break;
                case 2:
                    setText(R.id.tv_sim2_stat, label);
                    setProgress(R.id.pb_sim2, (int) pct, SimUtils.getSimColor(2));
                    break;
            }
        }

        // Device ID
        setText(R.id.tv_device_id_stat, "Device ID: " + prefs.getDeviceId());
    }

    private void setText(int id, String text) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setText(text);
    }

    private void setProgress(int id, int progress, String color) {
        ProgressBar pb = findViewById(id);
        if (pb == null) return;
        pb.setProgress(progress);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            pb.setProgressTintList(
                android.content.res.ColorStateList.valueOf(Color.parseColor(color)));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadStats();
    }
}
