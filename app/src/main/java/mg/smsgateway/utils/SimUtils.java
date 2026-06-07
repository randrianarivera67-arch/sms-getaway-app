package mg.smsgateway.utils;

/**
 * Résolution du nom opérateur/service pour chaque slot SIM.
 * MVola = Telma (slot 0 par défaut)
 * Orange Money = Orange (slot 1 par défaut)
 * Airtel Money = Airtel (slot 2 — triple SIM)
 */
public class SimUtils {

    public static final String SIM_MVOLA   = "MVola (Telma)";
    public static final String SIM_ORANGE  = "Orange Money";
    public static final String SIM_AIRTEL  = "Airtel Money";

    /** Résout le nom du service mobile money selon le slot SIM */
    public static String getSimName(int slot) {
        switch (slot) {
            case 0:  return SIM_MVOLA;
            case 1:  return SIM_ORANGE;
            case 2:  return SIM_AIRTEL;
            default: return "SIM " + (slot + 1);
        }
    }

    /** Couleur hex associée à chaque SIM */
    public static String getSimColor(int slot) {
        switch (slot) {
            case 0:  return "#1E40AF"; // Bleu Telma
            case 1:  return "#EA580C"; // Orange
            case 2:  return "#DC2626"; // Rouge Airtel
            default: return "#64748B";
        }
    }

    /** Abréviation pour badge */
    public static String getSimBadge(int slot) {
        switch (slot) {
            case 0:  return "MV";
            case 1:  return "OR";
            case 2:  return "AI";
            default: return "S" + slot;
        }
    }

    /** Détecte le slot depuis l'adresse source (MVola = 034/038, Orange = 032, Airtel = 033) */
    public static int guessSlotFromNumber(String number) {
        if (number == null) return -1;
        // Normalize: remove +261
        String n = number.replaceAll("[^0-9]", "");
        if (n.startsWith("261")) n = "0" + n.substring(3);
        if (n.startsWith("034") || n.startsWith("038")) return 0; // Telma/MVola
        if (n.startsWith("032") || n.startsWith("037")) return 1; // Orange
        if (n.startsWith("033") || n.startsWith("036")) return 2; // Airtel
        return -1; // inconnu
    }
}
