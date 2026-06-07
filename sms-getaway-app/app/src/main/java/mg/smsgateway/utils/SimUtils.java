package mg.smsgateway.utils;

public class SimUtils {

    public static final String SIM_YAS    = "YAS (Telma)";
    public static final String SIM_ORANGE = "Orange Money";
    public static final String SIM_AIRTEL = "Airtel Money";

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
            case 0:  return "YAS";
            case 1:  return "OR";
            case 2:  return "AI";
            default: return "S" + slot;
        }
    }

    public static int guessSlotFromNumber(String number) {
        if (number == null) return -1;
        String upper = number.toUpperCase().trim();
        if (upper.equals("MVOLA") || upper.equals("TELMA") || upper.equals("YAS")
                || upper.startsWith("MVOLA") || upper.startsWith("TELMA")
                || upper.startsWith("YAS")) return 0;
        if (upper.equals("ORANGE") || upper.equals("OM")
                || upper.startsWith("ORANGE")) return 1;
        if (upper.equals("AIRTEL") || upper.startsWith("AIRTEL")) return 2;
        String n = number.replaceAll("[^0-9]", "");
        if (n.startsWith("261") && n.length() >= 11) n = "0" + n.substring(3);
        if (n.startsWith("034") || n.startsWith("038")) return 0;
        if (n.startsWith("032") || n.startsWith("037")) return 1;
        if (n.startsWith("033"))                        return 2;
        if (upper.contains("TELMA") || upper.contains("MVOLA") || upper.contains("YAS")) return 0;
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

    public static int getSlotFromOperatorName(String simName) {
        if (simName == null) return -1;
        if (simName.contains("YAS") || simName.contains("Telma") || simName.contains("MVola")) return 0;
        if (simName.contains("Orange")) return 1;
        if (simName.contains("Airtel")) return 2;
        return -1;
    }
}
