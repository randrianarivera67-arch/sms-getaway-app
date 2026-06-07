package mg.smsgateway.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import mg.smsgateway.R;
import mg.smsgateway.model.SmsMessage;
import mg.smsgateway.service.SmsReceiver;
import mg.smsgateway.utils.Prefs;
import mg.smsgateway.utils.SimUtils;
import mg.smsgateway.utils.SmsQueue;
import java.util.List;

public class InboxActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private InboxAdapter adapter;
    private TextView tvEmpty;
    private Prefs prefs;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SmsReceiver.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
                loadMessages();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inbox);
        prefs = new Prefs(this);

        // Effacer badge notification
        prefs.clearNotifCount();

        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        tvEmpty = findViewById(R.id.tv_empty);
        recyclerView = findViewById(R.id.recycler_inbox);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        loadMessages();
    }

    private void loadMessages() {
        List<SmsMessage> messages = SmsQueue.getInstance(this).getRecentMessages(100);
        if (adapter == null) {
            adapter = new InboxAdapter(messages);
            recyclerView.setAdapter(adapter);
        } else {
            adapter.setMessages(messages);
        }
        if (tvEmpty != null)
            tvEmpty.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            registerReceiver(refreshReceiver,
                new IntentFilter(SmsReceiver.SMS_RECEIVED_ACTION));
            receiverRegistered = true;
        }
        loadMessages();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            try { unregisterReceiver(refreshReceiver); } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
    }

    // ---- Adapter ----
    static class InboxAdapter extends RecyclerView.Adapter<InboxAdapter.VH> {
        private List<SmsMessage> messages;

        InboxAdapter(List<SmsMessage> messages) { this.messages = messages; }

        void setMessages(List<SmsMessage> messages) {
            this.messages = messages;
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_sms, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            SmsMessage sms = messages.get(position);
            h.tvFrom.setText(sms.getFrom() != null ? sms.getFrom() : "Inconnu");
            h.tvMessage.setText(sms.getMessage());
            h.tvTime.setText(sms.getTimestamp() != null
                ? sms.getTimestamp().replace("T", " ").replace("Z", "") : "");
            h.tvSim.setText(sms.getSimShortName());

            // Couleur badge SIM
            try {
                h.tvSim.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                        Color.parseColor(SimUtils.getSimColor(sms.getSimSlot()))));
            } catch (Exception ignored) {}

            // Statut
            String status = sms.getStatus();
            if ("sent".equals(status)) {
                h.tvStatus.setText("✓ Envoyé");
                h.tvStatus.setTextColor(Color.parseColor("#10B981"));
            } else if ("failed".equals(status)) {
                h.tvStatus.setText("✗ Échoué");
                h.tvStatus.setTextColor(Color.parseColor("#EF4444"));
            } else {
                h.tvStatus.setText("⏳ En attente");
                h.tvStatus.setTextColor(Color.parseColor("#F59E0B"));
            }

            // Animation d'entrée
            h.itemView.setAlpha(0f);
            h.itemView.animate().alpha(1f).setDuration(200)
                .setStartDelay(position * 30L).start();
        }

        @Override
        public int getItemCount() { return messages.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvFrom, tvMessage, tvTime, tvSim, tvStatus;
            VH(View v) {
                super(v);
                tvFrom    = v.findViewById(R.id.tv_sms_from);
                tvMessage = v.findViewById(R.id.tv_sms_message);
                tvTime    = v.findViewById(R.id.tv_sms_time);
                tvSim     = v.findViewById(R.id.tv_sms_sim);
                tvStatus  = v.findViewById(R.id.tv_sms_status);
            }
        }
    }
}
