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

    /** true si le PIN a effectivement ete saisi lors du dernier envoi interactif. */
    public static volatile boolean lastPinSubmitted = false;

    /** Nombre d'ecrans de saisie valides lors du dernier envoi interactif. */
    public static volatile int lastStepsDone = 0;

    /**
     * Application Telephone par defaut. Sert a viser explicitement le bon dialer
     * et a ne JAMAIS laisser apparaitre le selecteur "Continuer avec".
     */
    public static String getDefaultDialer(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.telecom.TelecomManager tcm = (android.telecom.TelecomManager)
                    context.getSystemService(Context.TELECOM_SERVICE);
                if (tcm != null) {
                    String p = tcm.getDefaultDialerPackage();
                    if (p != null && !p.isEmpty()) return p;
                }
            }
        } catch (Exception e) { Log.e(TAG, "getDefaultDialer: " + e.getMessage()); }
        return null;
    }

    /**
     * Compose le code USSD sans jamais passer par un selecteur d'application.
     * 1) TelecomManager.placeCall() : va directement au dialer par defaut (API 23+)
     * 2) repli : ACTION_CALL cible sur le paquet du dialer par defaut
     * @return false si aucune voie n'a pu etre utilisee.
     */
    @SuppressLint("MissingPermission")
    private static boolean composerUssd(Context context, String ussdCode, int subId) {
        android.net.Uri uri = android.net.Uri.parse("tel:" + android.net.Uri.encode(ussdCode));

        // 1) Voie officielle : aucun selecteur possible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                android.telecom.TelecomManager tcm = (android.telecom.TelecomManager)
                    context.getSystemService(Context.TELECOM_SERVICE);
                if (tcm != null) {
                    android.os.Bundle extras = new android.os.Bundle();
                    android.telecom.PhoneAccountHandle h = phoneAccountFor(context, subId);
                    if (h != null) {
                        extras.putParcelable(
                            android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h);
                    }
                    tcm.placeCall(uri, extras);
                    Log.d(TAG, "USSD compose via TelecomManager.placeCall");
                    return true;
                }
            } catch (SecurityException se) {
                Log.e(TAG, "placeCall permission: " + se.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "placeCall: " + e.getMessage());
            }
        }

        // 2) Repli : intent explicite vers le dialer par defaut
        try {
            android.content.Intent intent =
                new android.content.Intent(android.content.Intent.ACTION_CALL);
            intent.setData(uri);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            String dialer = getDefaultDialer(context);
            if (dialer != null) intent.setPackage(dialer);   // <- supprime le selecteur
            if (subId >= 0) applySimSelection(context, intent, subId);
            context.startActivity(intent);
            Log.d(TAG, "USSD compose via ACTION_CALL package=" + dialer);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "composerUssd repli: " + e.getMessage());
        }
        return false;
    }

    /** PhoneAccountHandle correspondant a une SIM (subId). */
    @SuppressLint("MissingPermission")
    private static android.telecom.PhoneAccountHandle phoneAccountFor(Context context, int subId) {
        if (subId < 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            android.telecom.TelecomManager tcm = (android.telecom.TelecomManager)
                context.getSystemService(Context.TELECOM_SERVICE);
            if (tcm == null) return null;
            List<android.telecom.PhoneAccountHandle> accounts = tcm.getCallCapablePhoneAccounts();
            if (accounts == null) return null;
            for (android.telecom.PhoneAccountHandle h : accounts) {
                String hid = h.getId();
                if (hid != null && hid.equals(String.valueOf(subId))) return h;
            }
        } catch (Exception e) { Log.e(TAG, "phoneAccountFor: " + e.getMessage()); }
        return null;
    }

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
                    // ----------------------------------------------------------
                    // ATTENTION — CE N'EST PAS LA PREUVE D'UN ECHEC DE PAIEMENT.
                    // ----------------------------------------------------------
                    // sendUssdRequest() signale un echec des que la SESSION ne se
                    // termine pas comme il l'attend. Or MVola repond, apres un
                    // transfert REUSSI, par un dernier ecran qui redemande une
                    // saisie ("... enregistrer dans votre repertoire ..."). Sur
                    // certains telephones, cette session non close remonte ici —
                    // alors que l'argent est bien parti.
                    //
                    // Aucun texte operateur n'est fourni : on ne peut donc RIEN
                    // affirmer. On le dit explicitement au serveur, qui laissera
                    // le retrait en attente du SMS de confirmation plutot que de
                    // le declarer perdu.
                    // ----------------------------------------------------------
                    Log.e(TAG, "USSD sans reponse lisible, code=" + failureCode
                             + " — issue INCONNUE, ne pas conclure a un echec");
                    callback.onResult(retraitId, false,
                        "USSD_ISSUE_INCONNUE: aucune reponse lisible de l'operateur (code "
                        + failureCode + "). Le transfert a peut-etre abouti : "
                        + "attendre le SMS de confirmation avant toute relance.");
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
        sendUssdInteractive(context, retraitId, ussdCode, operator, pin, "", 1, callback);
    }

    /**
     * @param menuReply reponse a taper sur un ecran de saisie qui n'est PAS une
     *                  demande de code secret. Vide = ne rien taper.
     * @param maxSteps  nombre d'ecrans de saisie a traiter.
     *                  Orange Money : 2. MVola : sans objet (PIN dans le code).
     */
    @SuppressLint("MissingPermission")
    public static void sendUssdInteractive(Context context, String retraitId,
                                           String ussdCode, String operator,
                                           String pin, String menuReply, int maxSteps,
                                           UssdCallback callback) {
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

            // Arme AVANT la composition : la boite peut apparaitre tres vite.
            // arm() refuse si un autre retrait est deja en cours -> on abandonne
            // plutot que de risquer de taper le PIN dans la boite du voisin.
            if (!UssdAccessibilityService.arm(pin, menuReply, maxSteps, retraitId)) {
                callback.onResult(retraitId, false,
                    "Un autre retrait USSD est deja en cours sur ce telephone. "
                    + "Retrait NON envoye (aucun risque de double paiement).");
                return;
            }

            // Selection de la SIM : methode officielle (API 23+) puis replis constructeurs
            int subId = getSubIdForOperator(context, operator);

            // ----------------------------------------------------------------
            // FIX "la passerelle repasse en manuel"
            // ----------------------------------------------------------------
            // startActivity(ACTION_CALL) sans destinataire explicite laisse
            // Android afficher le selecteur "Continuer avec : CallApp /
            // Gestion des appels". Ce selecteur N'A PAS de champ de saisie :
            // le service d'accessibilite ne le reconnait pas, ne tape rien, et
            // tout reste fige jusqu'a ce qu'un humain touche l'ecran.
            // Pire : le selecteur PERD les extras de choix de SIM, donc le code
            // pouvait partir sur la mauvaise SIM.
            // On vise donc explicitement l'application Telephone par defaut.
            // ----------------------------------------------------------------
            if (!composerUssd(context, ussdCode, subId)) {
                UssdAccessibilityService.disarm();
                callback.onResult(retraitId, false,
                    "Impossible de composer le code USSD : aucune application Telephone par defaut. "
                    + "Reglages > Applications > Applications par defaut > Telephone.");
                return;
            }
            Log.d(TAG, "USSD interactif compose (" + operator + ") pour " + retraitId);

            // Conclusion : soit des que l'operateur confirme le depart du transfert
            // (on n'attend alors pas les 25 s : le retrait suivant peut demarrer),
            // soit au bout du delai maximum.
            final android.os.Handler hh = new android.os.Handler(android.os.Looper.getMainLooper());
            final boolean[] conclu = { false };
            final Runnable conclure = new Runnable() {
                @Override public void run() {
                    if (conclu[0]) return;
                    conclu[0] = true;
                    hh.removeCallbacksAndMessages(null);
                    terminerInteractif(retraitId, maxSteps, callback);
                }
            };
            // Sondage : des que la transaction est partie, on conclut tout de suite
            final Runnable sonde = new Runnable() {
                @Override public void run() {
                    if (conclu[0]) return;
                    // Conclure des que le sort de la transaction est connu :
                    // transfert parti OU echec definitif annonce. Attendre les
                    // 25 s dans ces deux cas ne sert qu'a retarder le retrait
                    // suivant.
                    if (UssdAccessibilityService.estConclu()) {
                        hh.postDelayed(conclure, 1200L);   // laisse le clic se faire
                        return;
                    }
                    hh.postDelayed(this, 1000L);
                }
            };
            hh.postDelayed(sonde, 2000L);
            hh.postDelayed(conclure, 25000L);
        } catch (Exception e) {
            Log.e(TAG, "sendUssdInteractive: " + e.getMessage());
            UssdAccessibilityService.disarm();
            callback.onResult(retraitId, false, "Erreur USSD interactif: " + e.getMessage());
        }
    }

    /**
     * Motif technique du dernier envoi (explication destinee a l'admin).
     * SEPARE du texte operateur : l'admin doit voir le dernier message USSD
     * BRUT, pas un melange explication + texte.
     */
    public static volatile String lastMotif = "";

    /** Construit le compte rendu final d'un envoi interactif. */
    private static void terminerInteractif(String retraitId, int maxSteps, UssdCallback callback) {
        boolean pinTape  = UssdAccessibilityService.wasPinSubmitted();
        boolean partie   = UssdAccessibilityService.wasTransactionInitiee();
        boolean echoue   = UssdAccessibilityService.wasTransactionEchouee();
        int     etapes   = UssdAccessibilityService.getStepsDone();
        String  nonTraite= UssdAccessibilityService.getEcranNonTraite();
        // getReportText() = ecran vu APRES la saisie. Avec getLastDialogText()
        // on renvoyait l'invite "Ampidiro ny kaody miafina" elle-meme, et le
        // serveur pouvait croire que le PIN n'avait jamais ete saisi.
        String  resp     = UssdAccessibilityService.getReportText();
        UssdAccessibilityService.disarm();

        boolean ok = pinTape;
        String motif = "";

        // ----------------------------------------------------------------
        // resp = TEXTE OPERATEUR BRUT, jamais autre chose.
        // C'est lui que l'admin voit dans la colonne "dernier message USSD" :
        // y melanger une explication rendait le vrai message illisible.
        // L'explication part separement dans 'motif'.
        // ----------------------------------------------------------------
        if (resp == null) resp = "";
        if (resp.trim().isEmpty()) {
            motif = ok ? "PIN saisi mais aucun texte operateur n'a pu etre lu."
                       : "Aucune boite de dialogue USSD detectee. Verifiez : application "
                         + "Telephone par defaut, service d'accessibilite MATULMADA, "
                         + "affichage par-dessus les autres applications.";
        }

        if (echoue) {
            // L'operateur a explicitement refuse (solde insuffisant, PIN errone...).
            // Ce verdict prime sur tout le reste : l'argent n'est PAS parti.
            ok = false;
            motif = "Refus de l'operateur.";
        } else if (partie) {
            // L'operateur a confirme le depart du transfert. Cas le plus sur :
            // ni l'ecran de repertoire non rempli, ni un quota d'ecrans non
            // atteint ne doivent transformer cela en echec — le client a bien
            // recu son argent.
            ok = true;
            motif = "Transfert confirme par l'operateur.";
        } else if (nonTraite != null && !nonTraite.isEmpty()) {
            ok = false;
            // Le texte de l'ecran non reconnu devient le message operateur :
            // c'est bien le dernier ecran affiche.
            if (resp.trim().isEmpty()) resp = nonTraite;
            motif = "Ecran de saisie non reconnu, aucune reponse de menu configuree "
                  + "pour cet operateur.";
        } else if (ok && etapes < maxSteps) {
            ok = false;
            motif = "Seulement " + etapes + " ecran(s) sur " + maxSteps
                  + " ont ete valides : transaction incomplete.";
        }

        lastMotif = motif;

        lastPinSubmitted = pinTape;
        lastStepsDone    = etapes;
        Log.d(TAG, "USSD interactif termine ok=" + ok + " partie=" + partie
                + " etapes=" + etapes + "/" + maxSteps);
        // Le statut definitif reste donne par le SMS de l'operateur cote serveur :
        // on ne declare jamais un succes de paiement ici.
        callback.onResult(retraitId, ok, resp);
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
