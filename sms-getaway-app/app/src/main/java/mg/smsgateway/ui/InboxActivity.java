package mg.smsgateway.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import mg.smsgateway.R;
import mg.smsgateway.model.SmsMessage;
import mg.smsgateway.service.SmsReceiver;
import mg.smsgateway.utils.Prefs;
import mg.smsgateway.utils.SimUtils;
import mg.smsgateway.utils.SmsQueue;
import java.util.ArrayList;
import java.util.List;

public class InboxActivity extends AppCompatActivity {

    public static final String EXTRA_FILTER = "filter";

    private RecyclerView recyclerView;
    private InboxAdapter adapter;
    private TextView tvEmpty;
    private Prefs prefs;
    private String currentFilter = "all";
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
        prefs.clearNotifCount();

        currentFilter = getIntent().getStringExtra(EXTRA_FILTER);
        if (currentFilter == null) currentFilter = "all";

        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        tvEmpty = findViewById(R.id.tv_empty);
        recyclerView = findViewById(R.id.recycler_inbox);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        loadMessages();
    }

    private void loadMessages() {
        SmsQueue queue = SmsQueue.getInstance(this);
        List<SmsMessage> all = queue.getRecentMessages(200);
        List<SmsMessage> filtered = new ArrayList<>();
        for (SmsMessage sms : all) {
            String status = sms.getStatus();
            switch (currentFilter) {
                case "sent":    if ("sent".equals(status))    filtered.add(sms); break;
                case "pending": if ("pending".equals(status)) filtered.add(sms); break;
                case "failed":  if ("failed".equals(status))  filtered.add(sms); break;
                default: filtered.add(sms); break;
            }
        }
        if (adapter == null) {
            adapter = new InboxAdapter(filtered, this::onSmsClicked);
            recyclerView.setAdapter(adapter);
        } else {
            adapter.setMessages(filtered);
        }
        if (tvEmpty != null)
            tvEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void onSmsClicked(SmsMessage sms) {
        int slot = sms.getSimSlot();
        if (slot < 0) slot = SimUtils.guessSlotFromNumber(sms.getFrom());
        String operatorColor = SimUtils.getSimColor(slot >= 0 ? slot : 0);
        String operatorName  = slot >= 0 ? SimUtils.getSimName(slot)
                                         : SimUtils.getOperatorFromNumber(sms.getFrom());
        String statusText, statusColor;
        switch (sms.getStatus()) {
            case "sent":    statusText = "✓ Transmis";   statusColor = "#10B981"; break;
            case "failed":  statusText = "✗ Échoué";     statusColor = "#EF4444"; break;
            default:        statusText = "⏳ En attente"; statusColor = "#F59E0B"; break;
        }

        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_sms_detail, null);

        TextView tvOperator = dialogView.findViewById(R.id.detail_operator);
        TextView tvFrom     = dialogView.findViewById(R.id.detail_from);
        TextView tvMessage  = dialogView.findViewById(R.id.detail_message);
        TextView tvTime     = dialogView.findViewById(R.id.detail_time);
        TextView tvStatus   = dialogView.findViewById(R.id.detail_status);

        if (tvOperator != null) {
            tvOperator.setText(operatorName);
            try {
                tvOperator.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                        Color.parseColor(operatorColor)));
            } catch (Exception ignored) {}
        }
        if (tvFrom    != null) tvFrom.setText(sms.getFrom() != null ? sms.getFrom() : "Inconnu");
        if (tvMessage != null) tvMessage.setText(sms.getMessage());
        if (tvTime    != null) tvTime.setText(sms.getTimestamp() != null
                ? sms.getTimestamp().replace("T"," ").replace("Z","") : "");
        if (tvStatus  != null) {
            tvStatus.setText(statusText);
            try { tvStatus.setTextColor(Color.parseColor(statusColor)); }
            catch (Exception ignored) {}
        }

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Répondre", (d, w) -> showReplyDialog(sms))
                .setNeutralButton("Supprimer", (d, w) -> new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Supprimer").setMessage("Supprimer ce SMS ?").setPositiveButton("Oui", (d2, w2) -> { mg.smsgateway.utils.SmsQueue.getInstance(this).deleteSms(sms.getId()); loadMessages(); }).setNegativeButton("Non", null).show())
                .setNegativeButton("Fermer", null)
                .create().show();
    }

    private void showReplyDialog(SmsMessage original) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Votre réponse...");
        input.setMinLines(3);
        input.setPadding(48, 24, 48, 24);
        new AlertDialog.Builder(this)
                .setTitle("Répondre à " + original.getFrom())
                .setView(input)
                .setPositiveButton("Envoyer", (d, w) -> {
                    String reply = input.getText().toString().trim();
                    if (!reply.isEmpty()) sendSmsReply(original.getFrom(), reply);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void sendSmsReply(String to, String message) {
        try {
            SmsManager sm = SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(message);
            sm.sendMultipartTextMessage(to, null, parts, null, null);
            Toast.makeText(this, "✓ SMS envoyé à " + to, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Échec: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            registerReceiver(refreshReceiver, new IntentFilter(SmsReceiver.SMS_RECEIVED_ACTION));
            receiverRegistered = true;
        }
        loadMessages();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            try { unregisterReceiver(refreshReceiver); }
            catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
    }

    interface OnSmsClickListener { void onClick(SmsMessage sms); }

    static class InboxAdapter extends RecyclerView.Adapter<InboxAdapter.VH> {
        private List<SmsMessage> messages;
        private final OnSmsClickListener listener;

        InboxAdapter(List<SmsMessage> messages, OnSmsClickListener listener) {
            this.messages = messages;
            this.listener = listener;
        }

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
                ? sms.getTimestamp().replace("T"," ").replace("Z","") : "");

            int slot = sms.getSimSlot();
            if (slot < 0) slot = SimUtils.guessSlotFromNumber(sms.getFrom());
            String opName  = slot >= 0 ? SimUtils.getSimName(slot)
                                       : SimUtils.getOperatorFromNumber(sms.getFrom());
            String opColor = SimUtils.getSimColor(slot >= 0 ? slot : 3);
            h.tvSim.setText(opName);
            try {
                h.tvSim.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(opColor)));
            } catch (Exception ignored) {}

            String status = sms.getStatus();
            if ("sent".equals(status)) {
                h.tvStatus.setText("✓ Transmis");
                h.tvStatus.setTextColor(Color.parseColor("#10B981"));
            } else if ("failed".equals(status)) {
                h.tvStatus.setText("✗ Échoué");
                h.tvStatus.setTextColor(Color.parseColor("#EF4444"));
            } else {
                h.tvStatus.setText("⏳ En attente");
                h.tvStatus.setTextColor(Color.parseColor("#F59E0B"));
            }

            h.itemView.setOnClickListener(v -> listener.onClick(sms));
            h.itemView.setAlpha(0f);
            h.itemView.animate().alpha(1f).setDuration(200).setStartDelay(position * 30L).start();
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
