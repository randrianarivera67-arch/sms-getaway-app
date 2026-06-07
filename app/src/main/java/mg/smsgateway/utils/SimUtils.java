package mg.smsgateway.utils;

/**
 * Détection opérateur Madagascar par préfixe numéro.
 * YAS (Telma/MVola) : +26134xxxxxxx, +26138xxxxxxx
 * Orange Money      : +26132xxxxxxx, +26127xxxxxxx
 * Airtel Money      : +26133xxxxxxx
 */
public class SimUtils {

    public static final String SIM_YAS    = "YAS (Telma)";
    public static final String SIM_ORANGE = "Orange Money";
    public static final String SIM_AIRTEL = "Airtel Money";

    /** Résout le nom du service mobile money selon le slot SIM */
    public static String getSimName(int slot) {
        switch (slot) {
            case 0:  return SIM_YAS;
            case 1:  return SIM_ORANGE;
            case 2:  return SIM_AIRTEL;
            default: return "SIM " + (slot + 1);
        }
    }

    /** Couleur hex associée à chaque opérateur */
    public static String getSimColor(int slot) {
        switch (slot) {
            case 0:  return "#1E40AF"; // Bleu YAS/Telma
            case 1:  return "#EA580C"; // Orange
            case 2:  return "#DC2626"; // Rouge Airtel
            default: return "#64748B";
        }
    }

    /** Abréviation pour badge */
    public static String getSimBadge(int slot) {
        switch (slot) {
            case 0:  return "YAS";
            case 1:  return "OR";
            case 2:  return "AI";
            default: return "S" + slot;
        }
    }

    /**
     * Détecte l'opérateur depuis le numéro expéditeur.
     * YAS (Telma) : +26134, +26138  → slot 0
     * Orange      : +26132, +26127  → slot 1
     * Airtel      : +26133          → slot 2
     */
    public static int guessSlotFromNumber(String number) {
        if (number == null) return -1;

        // Normalise: garde uniquement les chiffres
        String n = number.replaceAll("[^0-9]", "");

        // +261XXXXXXXX → 0XXXXXXXX
        if (n.startsWith("261") && n.length() >= 11) {
            n = "0" + n.substring(3);
        }

        // YAS / Telma / MVola
        if (n.startsWith("034") || n.startsWith("038")) return 0;

        // Orange Money
        if (n.startsWith("032") || n.startsWith("037")) return 1;

        // Airtel Money
        if (n.startsWith("033")) return 2;

        return -1; // inconnu
    }

    /**
     * Détecte le nom de l'opérateur directement depuis le numéro.
     * Utile quand on n'a pas le slot SIM physique.
     */
    public static String getOperatorFromNumber(String number) {
        int slot = guessSlotFromNumber(number);
        if (slot >= 0) return getSimName(slot);

        // Fallback: cherche dans le numéro des indices textuels
        if (number != null) {
            String upper = number.toUpperCase();
            if (upper.contains("YAS") || upper.contains("TELMA") || upper.contains("MVOLA"))
                return SIM_YAS;
            if (upper.contains("ORANGE")) return SIM_ORANGE;
            if (upper.contains("AIRTEL")) return SIM_AIRTEL;
        }
        return "Inconnu";
    }

    /** Retourne la couleur hex depuis le numéro expéditeur */
    public static String getColorFromNumber(String number) {
        int slot = guessSlotFromNumber(number);
        return getSimColor(slot >= 0 ? slot : 3);
    }

    /** Retourne le slot correspondant au nom de l'opérateur */
    public static int getSlotFromOperatorName(String simName) {
        if (simName == null) return -1;
        if (simName.contains("YAS") || simName.contains("Telma") || simName.contains("MVola"))
            return 0;
        if (simName.contains("Orange")) return 1;
        if (simName.contains("Airtel")) return 2;
        return -1;
    }
}
