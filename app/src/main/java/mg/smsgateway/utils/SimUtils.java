package mg.smsgateway.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Détection opérateur Madagascar — SubscriptionManager + préfixe + nom expéditeur.
 */
public class SimUtils {

    public static final String SIM_YAS    = "MVola YAS";
    public static final String SIM_ORANGE = "Orange Money";
    public static final String SIM_AIRTEL = "Airtel Money";

    // Cache subId → operator name
    private static final Map<Integer, String> subIdCache = new HashMap<>();

    /**
     * Initialise le cache depuis SubscriptionManager.
     * À appeler au démarrage de l'app.
     */
    @SuppressLint("MissingPermission")
    public static void initSubscriptions(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return;
        try {
            SubscriptionManager sm = (SubscriptionManager)
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return;
            List<SubscriptionInfo> sims = sm.getActiveSubscriptionInfoList();
            if (sims == null) return;
            subIdCache.clear();
            for (SubscriptionInfo info : sims) {
                String name = info.getDisplayName() != null
                    ? info.getDisplayName().toString() : "";
                String carrier = info.getCarrierName() != null
                    ? info.getCarrierName().toString() : "";
                String combined = (name + " " + carrier).toUpperCase();
                String operator;
                if (combined.contains("ORANGE")) operator = SIM_ORANGE;
                else if (combined.contains("TELMA") || combined.contains("MVOLA")
                      || combined.contains("YAS"))   operator = SIM_YAS;
                else if (combined.contains("AIRTEL")) operator = SIM_AIRTEL;
                else operator = name.isEmpty() ? "SIM " + (info.getSimSlotIndex()+1) : name;
                subIdCache.put(info.getSubscriptionId(), operator);
            }
        } catch (Exception e) {
            // Permission manquante ou erreur
        }
    }

    /**
     * Retourne le nom opérateur depuis le subscriptionId (Android 5.1+).
     */
    public static String getOperatorFromSubId(int subId) {
        String op = subIdCache.get(subId);
        return op != null ? op : "Inconnu";
    }

    public static String getOperatorFromSubId(android.content.Context context, int subId) {
        // Essaie le cache d'abord
        String cached = subIdCache.get(subId);
        if (cached != null) return cached;
        // Sinon via SubscriptionManager
        try {
            android.telephony.SubscriptionManager sm =
                (android.telephony.SubscriptionManager)
                context.getSystemService(android.content.Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return "Inconnu";
            @android.annotation.SuppressLint("MissingPermission")
            android.telephony.SubscriptionInfo info = sm.getActiveSubscriptionInfo(subId);
            if (info == null) return "Inconnu";
            String number = info.getNumber();
            if (number != null && !number.isEmpty()) return getOperatorFromNumber(number);
            String carrier = info.getCarrierName() != null ? info.getCarrierName().toString() : "";
            String upper = carrier.toUpperCase();
            if (upper.contains("TELMA") || upper.contains("YAS") || upper.contains("MVOLA")) return SIM_YAS;
            if (upper.contains("ORANGE")) return SIM_ORANGE;
            if (upper.contains("AIRTEL")) return SIM_AIRTEL;
        } catch (Exception e) { }
        return "Inconnu";
    }

    /**
     * Retourne le slot depuis le subscriptionId.
     */
    @SuppressLint("MissingPermission")
    public static int getSlotFromSubId(Context context, int subId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return -1;
        try {
            SubscriptionManager sm = (SubscriptionManager)
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return -1;
            SubscriptionInfo info = sm.getActiveSubscriptionInfo(subId);
            if (info != null) return info.getSimSlotIndex();
        } catch (Exception ignored) {}
        return -1;
    }

    public static String getSimName(int slot) {
        switch (slot) {
            case 0:  return SIM_YAS;
            case 1:  return SIM_ORANGE;
            case 2:  return SIM_AIRTEL;
            default: return "SIM " + (slot + 1);
        }
    }

    public static String getSimColor(int slot) {
        switch (slot) {
            case 0:  return "#1E40AF";
            case 1:  return "#EA580C";
            case 2:  return "#DC2626";
            default: return "#64748B";
        }
    }

    public static String getSimBadge(int slot) {
        switch (slot) {
            case 0:  return "MVola YAS";
            case 1:  return "OR";
            case 2:  return "AI";
            default: return "S" + slot;
        }
    }

    public static int guessSlotFromNumber(String number) {
        if (number == null) return -1;
        String upper = number.toUpperCase().trim();

        if (upper.equals("MVOLA") || upper.equals("TELMA") || upper.equals("MVola YAS")
                || upper.equals("MVO") || upper.startsWith("TELMA")
                || upper.startsWith("MVOLA") || upper.startsWith("MVola YAS")) return 0;

        if (upper.equals("ORANGE") || upper.equals("OM")
                || upper.startsWith("ORANGE") || upper.equals("ORANGEMONEY")) return 1;

        if (upper.equals("AIRTEL") || upper.startsWith("AIRTEL")) return 2;

        String n = number.replaceAll("[^0-9]", "");
        if (n.startsWith("261") && n.length() >= 11) n = "0" + n.substring(3);

        if (n.startsWith("034") || n.startsWith("038")) return 0;
        if (n.startsWith("032") || n.startsWith("037")) return 1;
        if (n.startsWith("033"))                         return 2;

        if (upper.contains("TELMA") || upper.contains("MVOLA") || upper.contains("MVola YAS")) return 0;
        if (upper.contains("ORANGE")) return 1;
        if (upper.contains("AIRTEL")) return 2;

        return -1;
    }

    public static String getOperatorFromNumber(String number) {
        int slot = guessSlotFromNumber(number);
        return slot >= 0 ? getSimName(slot) : "Inconnu";
    }

    public static String getColorFromNumber(String number) {
        int slot = guessSlotFromNumber(number);
        return getSimColor(slot >= 0 ? slot : 3);
    }

    public static String getColorFromOperator(String operator) {
        if (operator == null) return "#64748B";
        String op = operator.toUpperCase();
        if (op.contains("ORANGE")) return "#EA580C";
        if (op.contains("TELMA") || op.contains("MVOLA") || op.contains("MVola YAS")) return "#1E40AF";
        if (op.contains("AIRTEL")) return "#DC2626";
        return "#64748B";
    }

    public static int getSlotFromOperatorName(String simName) {
        if (simName == null) return -1;
        if (simName.contains("MVola YAS") || simName.contains("Telma") || simName.contains("MVola")) return 0;
        if (simName.contains("Orange")) return 1;
        if (simName.contains("Airtel")) return 2;
        return -1;
    }
    @android.annotation.SuppressLint("MissingPermission")
    public static org.json.JSONArray getSimStatuses(android.content.Context context) {
        java.util.Set<String> activeOperators = new java.util.HashSet<>();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                initSubscriptions(context);
                android.telephony.SubscriptionManager sm = (android.telephony.SubscriptionManager)
                    context.getSystemService(android.content.Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) {
                    java.util.List<android.telephony.SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
                    if (list != null) {
                        for (android.telephony.SubscriptionInfo info : list) {
                            String op = getOperatorFromSubId(info.getSubscriptionId());
                            activeOperators.add(op);
                        }
                    }
                }
            } catch (Exception e) {}
        }
        // index 0=MVola YAS, 1=Orange Money, 2=Airtel Money — order tsy miankina amin'ny slot fa amin'ny operator detected
        String[] names = {SIM_YAS, SIM_ORANGE, SIM_AIRTEL};
        int[] colors = {0, 1, 2};
        org.json.JSONArray arr = new org.json.JSONArray();
        for (int i = 0; i < 3; i++) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("slot",   i);
                obj.put("name",   names[i]);
                obj.put("active", activeOperators.contains(names[i]));
                obj.put("color",  getSimColor(colors[i]));
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        return arr;
    }

    @android.annotation.SuppressLint("MissingPermission")
    public static String getSimsStatusString(android.content.Context context) {
        org.json.JSONArray arr = getSimStatuses(context);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            try {
                org.json.JSONObject obj = arr.getJSONObject(i);
                if (sb.length() > 0) sb.append(",");
                sb.append(obj.getString("name"))
                  .append(":")
                  .append(obj.getBoolean("active") ? "active" : "inactive");
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

}