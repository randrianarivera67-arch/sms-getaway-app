package mg.smsgateway.utils;

/**
 * Détection opérateur Madagascar — préfixe + nom expéditeur.
 *
 * Préfixes valides Madagascar:
 *   Telma/YAS/MVola : 034, 038
 *   Orange Money    : 032, 037
 *   Airtel Money    : 033
 *
 * Noms expéditeurs connus (SMS opérateur):
 *   Telma/MVola : MVOLA, TELMA, YAS, MVO, TELMA-MG
 *   Orange      : ORANGE, OM, ORANGEMONEY, ORANGE-MG
 *   Airtel      : AIRTEL, AIRTELMONEY, AIRTEL-MG
 */
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
            case 0:  return "#1E40AF"; // Bleu Telma/YAS
            case 1:  return "#EA580C"; // Orange
            case 2:  return "#DC2626"; // Rouge Airtel
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

    /**
     * Détection principale — essaie préfixe puis nom textuel.
     * Retourne 0=YAS/Telma, 1=Orange, 2=Airtel, -1=inconnu
     */
    public static int guessSlotFromNumber(String number) {
        if (number == null) return -1;

        // 1. Détection par nom expéditeur (SMS opérateur sans numéro)
        String upper = number.toUpperCase().trim();

        // Telma / MVola / YAS
        if (upper.equals("MVOLA") || upper.equals("TELMA") || upper.equals("YAS")
                || upper.equals("MVO") || upper.startsWith("TELMA")
                || upper.startsWith("MVOLA") || upper.startsWith("YAS")) return 0;

        // Orange Money
        if (upper.equals("ORANGE") || upper.equals("OM")
                || upper.startsWith("ORANGE") || upper.equals("ORANGEMONEY")) return 1;

        // Airtel Money
        if (upper.equals("AIRTEL") || upper.startsWith("AIRTEL")) return 2;

        // 2. Détection par préfixe numérique
        String n = number.replaceAll("[^0-9]", "");

        // +261XXXXXXXX → 0XXXXXXXX
        if (n.startsWith("261") && n.length() >= 11)
            n = "0" + n.substring(3);

        if (n.startsWith("034") || n.startsWith("038")) return 0; // Telma/YAS
        if (n.startsWith("032") || n.startsWith("037")) return 1; // Orange
        if (n.startsWith("033"))                         return 2; // Airtel

        // 3. Fallback texte partiel (ex: "Orange-Notif", "Airtel-CI")
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
