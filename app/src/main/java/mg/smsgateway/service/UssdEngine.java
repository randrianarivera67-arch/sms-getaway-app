package mg.smsgateway.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import androidx.annotation.RequiresApi;
import java.util.List;

public class UssdEngine {

    private static final String TAG = "UssdEngine";

    public interface UssdCallback {
        void onResult(String retraitId, boolean success, String response);
    }

    @SuppressLint("MissingPermission")
    private static int getSubIdForOperator(Context context, String operator) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return -1;
        try {
            SubscriptionManager sm = (SubscriptionManager)
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return -1;
            List<SubscriptionInfo> sims = sm.getActiveSubscriptionInfoList();
            if (sims == null) return -1;
            String op = (operator == null ? "" : operator).toUpperCase();
            for (SubscriptionInfo info : sims) {
                String simOp = mg.smsgateway.utils.SimUtils
                    .getOperatorFromSubId(context, info.getSubscriptionId()).toUpperCase();
                // MVola YAS / Telma
                if ((op.contains("ORANGE")) && simOp.contains("ORANGE"))
                    return info.getSubscriptionId();
                if ((op.contains("MVOLA") || op.contains("YAS") || op.contains("TELMA"))
                    && (simOp.contains("MVOLA") || simOp.contains("YAS") || simOp.contains("TELMA")))
                    return info.getSubscriptionId();
                if (op.contains("AIRTEL") && simOp.contains("AIRTEL"))
                    return info.getSubscriptionId();
            }
        } catch (Exception e) { Log.e(TAG, "getSubIdForOperator: " + e.getMessage()); }
        return -1;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void sendUssd(Context context, String retraitId,
                                String ussdCode, UssdCallback callback) {
        sendUssd(context, retraitId, ussdCode, null, callback);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void sendUssd(Context context, String retraitId,
                                String ussdCode, String operator, UssdCallback callback) {
        try {
            TelephonyManager baseTm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
            if (baseTm == null) {
                callback.onResult(retraitId, false, "TelephonyManager null");
                return;
            }
            // Fampiasana operator name rehefa misy
            int subId = operator != null
                ? getSubIdForOperator(context, operator)
                : getSubIdForOperatorFromUssd(context, ussdCode);
            TelephonyManager tm = subId >= 0
                ? baseTm.createForSubscriptionId(subId) : baseTm;
            tm.sendUssdRequest(ussdCode, new TelephonyManager.UssdResponseCallback() {
                @Override
                public void onReceiveUssdResponse(TelephonyManager tm,
                                                  String request, CharSequence response) {
                    String resp = response != null ? response.toString() : "";
                    Log.d(TAG, "USSD response: " + resp);
                    callback.onResult(retraitId, true, resp);
                }
                @Override
                public void onReceiveUssdResponseFailed(TelephonyManager tm,
                                                        String request, int failureCode) {
                    Log.e(TAG, "USSD failed: " + failureCode);
                    callback.onResult(retraitId, false, "USSD failed: " + failureCode);
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Exception e) {
            Log.e(TAG, "sendUssd error: " + e.getMessage());
            callback.onResult(retraitId, false, e.getMessage());
        }
    }

    // Fallback: detect operator depuis prefix USSD (tsy ampiasaina intsony)
    @SuppressLint("MissingPermission")
    private static int getSubIdForOperatorFromUssd(Context context, String ussdCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return -1;
        try {
            SubscriptionManager sm = (SubscriptionManager)
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return -1;
            List<SubscriptionInfo> sims = sm.getActiveSubscriptionInfoList();
            if (sims == null || sims.isEmpty()) return -1;
            // Raha SIM iray ihany
            if (sims.size() == 1) return sims.get(0).getSubscriptionId();
            // Raha roa: ampiasaina prefix safidy
            for (SubscriptionInfo info : sims) {
                String simOp = mg.smsgateway.utils.SimUtils
                    .getOperatorFromSubId(context, info.getSubscriptionId()).toUpperCase();
                if (ussdCode.startsWith("#144") || ussdCode.startsWith("#145")) {
                    if (simOp.contains("ORANGE")) return info.getSubscriptionId();
                }
                if (ussdCode.startsWith("#111")) {
                    if (simOp.contains("MVOLA") || simOp.contains("YAS") || simOp.contains("TELMA"))
                        return info.getSubscriptionId();
                }
                if (ussdCode.startsWith("*123")) {
                    if (simOp.contains("AIRTEL")) return info.getSubscriptionId();
                }
            }
        } catch (Exception e) { Log.e(TAG, "getSubIdFromUssd: " + e.getMessage()); }
        return -1;
    }


    // ==================================================================
    // MODE INTERACTIF — pour les codes USSD qui demandent le PIN a l'invite
    // ==================================================================
    // TelephonyManager.sendUssdRequest() est une API "one-shot" : elle ne sait pas
    // repondre a une invite ("Ampidiro ny kaody miafina"). Orange refusant un code
    // contenant deja le PIN, on compose le code comme un appel : le systeme affiche
    // alors sa boite de dialogue USSD, et UssdAccessibilityService y saisit le PIN.
    @SuppressLint("MissingPermission")
    public static void sendUssdInteractive(Context context, String retraitId,
                                           String ussdCode, String operator,
                                           String pin, UssdCallback callback) {
        try {
            if (!UssdAccessibilityService.isEnabled(context)) {
                callback.onResult(retraitId, false,
                    "Service d'accessibilite MATULMADA desactive : impossible de saisir le PIN. " +
                    "Activez-le dans Reglages > Accessibilite.");
                return;
            }
            // Android 10+ : un service en arriere-plan ne peut ouvrir la boite
            // USSD que si l'app peut s'afficher par-dessus les autres apps.
            // Sans cela le systeme bloque SILENCIEUSEMENT : aucune boite, aucune
            // erreur, et le retrait resterait "processing" sans explication.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && !android.provider.Settings.canDrawOverlays(context)) {
                callback.onResult(retraitId, false,
                    "Autorisation \"Afficher par-dessus les autres applications\" desactivee : " +
                    "Android bloque l'ouverture du menu USSD. Activez-la pour MATULMADA " +
                    "dans Reglages > Applications > Acces special.");
                return;
            }
            if (pin == null || pin.trim().isEmpty()) {
                callback.onResult(retraitId, false, "PIN manquant pour le mode interactif");
                return;
            }

            // Arme AVANT la composition : la boite peut apparaitre tres vite
            UssdAccessibilityService.arm(pin, retraitId);

            android.content.Intent intent =
                new android.content.Intent(android.content.Intent.ACTION_CALL);
            intent.setData(android.net.Uri.parse("tel:" + android.net.Uri.encode(ussdCode)));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);

            // Selection de la SIM : methode officielle (API 23+) puis replis constructeurs
            int subId = getSubIdForOperator(context, operator);
            if (subId >= 0) {
                applySimSelection(context, intent, subId);
            }

            context.startActivity(intent);
            Log.d(TAG, "USSD interactif compose (" + operator + ") pour " + retraitId);

            // Laisse le temps au dialogue + saisie du PIN + reponse operateur
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                boolean ok = UssdAccessibilityService.wasPinSubmitted();
                String resp = UssdAccessibilityService.getLastDialogText();
                UssdAccessibilityService.disarm();
                if (resp == null || resp.trim().isEmpty()) {
                    resp = ok ? "PIN saisi (pas de texte lu)" : "Aucune boite de dialogue USSD detectee";
                }
                Log.d(TAG, "USSD interactif termine ok=" + ok);
                // Le statut definitif reste donne par le SMS de l'operateur cote serveur :
                // on ne declare jamais un succes de paiement ici.
                callback.onResult(retraitId, ok, resp);
            }, 25_000L);

        } catch (SecurityException se) {
            UssdAccessibilityService.disarm();
            Log.e(TAG, "sendUssdInteractive permission: " + se.getMessage());
            callback.onResult(retraitId, false, "Permission d'appel refusee (CALL_PHONE)");
        } catch (Exception e) {
            UssdAccessibilityService.disarm();
            Log.e(TAG, "sendUssdInteractive: " + e.getMessage());
            callback.onResult(retraitId, false, String.valueOf(e.getMessage()));
        }
    }

    /** Force la SIM utilisee pour la composition (officiel API 23+, replis OEM avant). */
    @SuppressLint("MissingPermission")
    private static void applySimSelection(Context context, android.content.Intent intent, int subId) {
        // Officiel : PhoneAccountHandle (Android 6+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                android.telecom.TelecomManager tcm = (android.telecom.TelecomManager)
                    context.getSystemService(Context.TELECOM_SERVICE);
                if (tcm != null) {
                    List<android.telecom.PhoneAccountHandle> accounts =
                        tcm.getCallCapablePhoneAccounts();
                    if (accounts != null) {
                        for (android.telecom.PhoneAccountHandle h : accounts) {
                            String hid = h.getId();
                            if (hid != null && hid.equals(String.valueOf(subId))) {
                                intent.putExtra(
                                    android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h);
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) { Log.e(TAG, "PhoneAccountHandle: " + e.getMessage()); }
        }
        // Replis constructeurs (Samsung, Xiaomi, anciennes ROM)
        try {
            int slot = getSlotIndex(context, subId);
            if (slot >= 0) {
                intent.putExtra("com.android.phone.extra.slot", slot);
                intent.putExtra("simSlot", slot);
                intent.putExtra("slot", slot);
                intent.putExtra("simId", slot);
            }
            intent.putExtra("subscription", subId);
            intent.putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", subId);
        } catch (Exception ignored) { }
    }

    @SuppressLint("MissingPermission")
    private static int getSlotIndex(Context context, int subId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return -1;
        try {
            SubscriptionManager sm = (SubscriptionManager)
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return -1;
            List<SubscriptionInfo> sims = sm.getActiveSubscriptionInfoList();
            if (sims == null) return -1;
            for (SubscriptionInfo info : sims) {
                if (info.getSubscriptionId() == subId) return info.getSimSlotIndex();
            }
        } catch (Exception e) { Log.e(TAG, "getSlotIndex: " + e.getMessage()); }
        return -1;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void checkBalance(Context context, String operator,
                                    String ussdCode, UssdCallback callback) {
        try {
            TelephonyManager baseTm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
            if (baseTm == null) {
                callback.onResult(operator, false, "TelephonyManager null");
                return;
            }
            int subId = getSubIdForOperator(context, operator);
            TelephonyManager tm = subId >= 0
                ? baseTm.createForSubscriptionId(subId) : baseTm;
            tm.sendUssdRequest(ussdCode, new TelephonyManager.UssdResponseCallback() {
                @Override
                public void onReceiveUssdResponse(TelephonyManager tm,
                                                  String request, CharSequence response) {
                    String resp = response != null ? response.toString() : "";
                    Log.d(TAG, "Balance [" + operator + "]: " + resp);
                    callback.onResult(operator, true, resp);
                }
                @Override
                public void onReceiveUssdResponseFailed(TelephonyManager tm,
                                                        String request, int failureCode) {
                    Log.e(TAG, "Balance USSD failed: " + failureCode);
                    callback.onResult(operator, false, "failed: " + failureCode);
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Exception e) {
            Log.e(TAG, "checkBalance error: " + e.getMessage());
            callback.onResult(operator, false, e.getMessage());
        }
    }
}
