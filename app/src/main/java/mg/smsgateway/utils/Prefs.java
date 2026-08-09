package mg.smsgateway.utils;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

public class Prefs {
    private static final String PREF_NAME       = "sms_gateway_prefs";
    private static final String KEY_SERVER_URL  = "server_url";
    private static final String KEY_API_KEY     = "api_key";
    private static final String KEY_DEVICE_ID   = "device_id";
    private static final String KEY_SMS_RECEIVED= "sms_received";
    private static final String KEY_SMS_SENT    = "sms_sent";
    private static final String KEY_SMS_PENDING = "sms_pending";
    private static final String KEY_SMS_FAILED  = "sms_failed";
    private static final String KEY_NOTIF_COUNT = "notif_count";
    private static final String KEY_AUTO_START  = "auto_start";
    private static final String KEY_RETRY_MAX   = "retry_max";
    // Stats par SIM
    private static final String KEY_SIM0_COUNT  = "sim0_count";
    private static final String KEY_SIM1_COUNT  = "sim1_count";
    private static final String KEY_SIM2_COUNT  = "sim2_count";
    // Stats horaires (JSON string, last 24h)
    private static final String KEY_HOURLY_STATS= "hourly_stats";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ---- Server config ----
    public String getServerUrl()           { return prefs.getString(KEY_SERVER_URL, ""); }
    public void   setServerUrl(String url) { prefs.edit().putString(KEY_SERVER_URL, url).apply(); }

    public String getApiKey()              { return prefs.getString(KEY_API_KEY, ""); }

    // ---- USSD Balance check codes ----
    public String getUssdBalance(String operator) {
        String defCode;
        switch (operator) {
            case "orange": defCode = "#144*5*3*2026#"; break;
            case "mvola":  defCode = "#111*1*6*1*2011#"; break;
            case "airtel": defCode = "*123#"; break;
            default: defCode = "";
        }
        return prefs.getString("ussd_balance_" + operator, defCode);
    }
    public void setUssdBalance(String operator, String code) {
        prefs.edit().putString("ussd_balance_" + operator, code).apply();
    }

    // Orange double portefeuille (APK master) : code solde marchand + etat toggle.
    public String getUssdBalanceMarchand()         { return prefs.getString("ussd_balance_orange_marchand", ""); }
    public void   setUssdBalanceMarchand(String c) { prefs.edit().putString("ussd_balance_orange_marchand", c == null ? "" : c).apply(); }
    public boolean isOrangeMarchand()              { return prefs.getBoolean("orange_marchand", false); }
    public void    setOrangeMarchand(boolean v)    { prefs.edit().putBoolean("orange_marchand", v).apply(); }

    // ---- USSD Check Solde toggle ----
    public boolean getUssdCheckEnabled() {
        return prefs.getBoolean("ussd_check_enabled", false);
    }
    public void setUssdCheckEnabled(boolean enabled) {
        prefs.edit().putBoolean("ussd_check_enabled", enabled).apply();
    }
    public long getUssdCheckIntervalMinutes() {
        return prefs.getLong("ussd_check_interval_min", 30);
    }
    public void setUssdCheckIntervalMinutes(long minutes) {
        prefs.edit().putLong("ussd_check_interval_min", minutes).apply();
    }
    public long getLastUssdCheckTime(String operator) {
        return prefs.getLong("ussd_check_last_" + operator, 0);
    }
    public void setLastUssdCheckTime(String operator, long timestamp) {
        prefs.edit().putLong("ussd_check_last_" + operator, timestamp).apply();
    }
    public void   setApiKey(String key)    { prefs.edit().putString(KEY_API_KEY, key).apply(); }

    public boolean getAutoStart()           { return prefs.getBoolean(KEY_AUTO_START, true); }
    public void    setAutoStart(boolean v)  { prefs.edit().putBoolean(KEY_AUTO_START, v).apply(); }

    public int  getRetryMax()              { return prefs.getInt(KEY_RETRY_MAX, 5); }
    public void setRetryMax(int max)       { prefs.edit().putInt(KEY_RETRY_MAX, max).apply(); }

    public String getDeviceId() {
        String id = prefs.getString(KEY_DEVICE_ID, "");
        if (id.isEmpty()) {
            id = "android-" + UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    // ---- Compteurs globaux ----
    public int  getSmsReceived()      { return prefs.getInt(KEY_SMS_RECEIVED, 0); }
    public void incrementSmsReceived(){ prefs.edit().putInt(KEY_SMS_RECEIVED, getSmsReceived() + 1).apply(); }

    public int  getSmsSent()          { return prefs.getInt(KEY_SMS_SENT, 0); }
    public void incrementSmsSent()    { prefs.edit().putInt(KEY_SMS_SENT, getSmsSent() + 1).apply(); }

    public int  getSmsPending()       { return prefs.getInt(KEY_SMS_PENDING, 0); }
    public void setSmsPending(int n)  { prefs.edit().putInt(KEY_SMS_PENDING, n).apply(); }

    public int  getSmsFailed()        { return prefs.getInt(KEY_SMS_FAILED, 0); }
    public void incrementSmsFailed()  { prefs.edit().putInt(KEY_SMS_FAILED, getSmsFailed() + 1).apply(); }

    // ---- Notifications non lues ----
    public int  getNotifCount()       { return prefs.getInt(KEY_NOTIF_COUNT, 0); }
    public void incrementNotifCount() { prefs.edit().putInt(KEY_NOTIF_COUNT, getNotifCount() + 1).apply(); }
    public void clearNotifCount()     { prefs.edit().putInt(KEY_NOTIF_COUNT, 0).apply(); }

    // ---- Stats par SIM ----
    public int  getSimCount(int slot) {
        String key = slot == 0 ? KEY_SIM0_COUNT : slot == 1 ? KEY_SIM1_COUNT : KEY_SIM2_COUNT;
        return prefs.getInt(key, 0);
    }
    public void incrementSimCount(int slot) {
        String key = slot == 0 ? KEY_SIM0_COUNT : slot == 1 ? KEY_SIM1_COUNT : KEY_SIM2_COUNT;
        prefs.edit().putInt(key, getSimCount(slot) + 1).apply();
    }

    // ---- Stats horaires (raw JSON) ----
    public String getHourlyStats()            { return prefs.getString(KEY_HOURLY_STATS, "[]"); }
    public void   setHourlyStats(String json) { prefs.edit().putString(KEY_HOURLY_STATS, json).apply(); }

    // ---- Reset ----
    public void resetStats() {
        prefs.edit()
            .putInt(KEY_SMS_RECEIVED, 0)
            .putInt(KEY_SMS_SENT, 0)
            .putInt(KEY_SMS_PENDING, 0)
            .putInt(KEY_SMS_FAILED, 0)
            .putInt(KEY_NOTIF_COUNT, 0)
            .putInt(KEY_SIM0_COUNT, 0)
            .putInt(KEY_SIM1_COUNT, 0)
            .putInt(KEY_SIM2_COUNT, 0)
            .putString(KEY_HOURLY_STATS, "[]")
            .apply();
    }
}
