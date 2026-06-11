package mg.smsgateway.model;

import org.json.JSONException;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public class SmsMessage {
    private String id;
    private String from;
    private String message;
    private String sim;        // "MVola", "Orange Money", "Airtel Money"
    private int simSlot;       // 0, 1, 2
    private String timestamp;
    private String status;
    private int retryCount;

    public SmsMessage(String from, String message, String sim, int simSlot) {
        this.id = UUID.randomUUID().toString();
        this.from = from;
        this.message = message;
        this.sim = sim;
        this.simSlot = simSlot;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        this.timestamp = sdf.format(new Date());
        this.status = "pending";
        this.retryCount = 0;
    }

    // Constructeur legacy (2 args) pour compatibilité interne
    public SmsMessage(String from, String message, String sim) {
        this(from, message, sim, -1);
    }

    public String getId()        { return id; }
    public String getTimestamp()   { return timestamp; }
    public void setTimestamp(String t) { this.timestamp = t; }
    public String getFrom()      { return from; }
    public String getMessage()   { return message; }
    public String getSim()       { return sim; }
    public int getSimSlot()      { return simSlot; }
    public String getStatus()    { return status; }
    public int getRetryCount()   { return retryCount; }

    public void setId(String id)                { this.id = id; }
    public void setStatus(String status)        { this.status = status; }
    public void setRetryCount(int retryCount)   { this.retryCount = retryCount; }
    public void incrementRetry()                { this.retryCount++; }

    /** Nom court pour l'affichage UI */
    public String getSimShortName() {
        if (sim == null) return "SIM";
        if (sim.contains("MVola"))  return "MVola";
        if (sim.contains("Orange")) return "Orange";
        if (sim.contains("Airtel")) return "Airtel";
        return sim;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("from", from);
        json.put("message", message);
        json.put("sim", sim);
        json.put("simSlot", simSlot);
        json.put("timestamp", timestamp);
        return json;
    }
}
