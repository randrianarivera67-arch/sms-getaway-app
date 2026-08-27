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

        // ------------------------------------------------------------------
        // REGLE DE SECURITE : ne jamais composer sur une SIM inconnue.
        // ------------------------------------------------------------------
        // Un code destine a un operateur, compose sur la SIM d'un autre, donne
        // au mieux une reponse inexploitable, au pire un mouvement d'argent
        // inattendu. Quand plusieurs SIM sont presentes et que l'on ne sait pas
        // laquelle viser, on renonce.
        // ------------------------------------------------------------------
        if (subId < 0 && compteSimsActives(context) > 1) {
            Log.e(TAG, "SIM cible inconnue et plusieurs SIM presentes : composition annulee");
            return false;
        }

        // ------------------------------------------------------------------
        // 1) VOIE PRINCIPALE : l'intention d'appel, avec les extras de SIM.
        // ------------------------------------------------------------------
        // C'est le chemin qui fonctionnait avant l'ajout de placeCall(), et
        // celui que les ROM constructeurs comprennent le mieux : applySimSelection
        // pose a la fois le compte telephonique officiel et les extras
        // proprietaires (slot, simSlot, subscription) que certaines ROM sont
        // seules a respecter.
        // setPackage() vise le telephone par defaut : plus aucun selecteur
        // "Continuer avec" ne peut apparaitre.
        // ------------------------------------------------------------------
        try {
            android.content.Intent intent =
                new android.content.Intent(android.content.Intent.ACTION_CALL);
            intent.setData(uri);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            String dialer = getDefaultDialer(context);
            if (dialer != null) intent.setPackage(dialer);
            if (subId >= 0) applySimSelection(context, intent, subId);
            context.startActivity(intent);
            Log.d(TAG, "USSD compose par intention, dialer=" + dialer + " SIM=" + subId);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "composition par intention: " + e.getMessage());
        }

        // ------------------------------------------------------------------
        // 2) SECOURS : placeCall(), uniquement si la SIM est formellement
        //    identifiee. Sans compte telephonique, placeCall choisit la SIM par
        //    defaut — ce qui enverrait le code au mauvais operateur.
        // ------------------------------------------------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                android.telecom.TelecomManager tcm = (android.telecom.TelecomManager)
                    context.getSystemService(Context.TELECOM_SERVICE);
                android.telecom.PhoneAccountHandle h = phoneAccountFor(context, subId);
                if (tcm != null && (h != null || compteSimsActives(context) <= 1)) {
                    android.os.Bundle extras = new android.os.Bundle();
                    if (h != null) {
                        extras.putParcelable(
                            android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h);
                    }
                    tcm.placeCall(uri, extras);
                    Log.d(TAG, "USSD compose par placeCall, SIM "
                             + (h != null ? "identifiee (" + subId + ")" : "unique"));
                    return true;
                }
            } catch (SecurityException se) {
                Log.e(TAG, "placeCall permission: " + se.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "placeCall: " + e.getMessage());
            }
        }
        return false;
    }

    /** PhoneAccountHandle correspondant a une SIM (subId). */
    /**
     * PhoneAccountHandle correspondant a une SIM.
     *
     * <p><b>Correction d'un defaut grave.</b> La version precedente comparait
     * uniquement <code>handle.getId()</code> au numero d'abonnement. Or, sur
     * beaucoup d'appareils (Motorola notamment), cet identifiant est l'ICCID
     * de la carte SIM, pas le numero d'abonnement. La comparaison echouait
     * donc silencieusement, aucun compte n'etait transmis a placeCall(), et
     * TOUS les codes USSD partaient sur la SIM par defaut.</p>
     *
     * <p>Consequence constatee : le code de consultation MVola compose sur la
     * SIM Orange, dont la reponse etait ensuite enregistree comme etant le
     * solde MVola. Le meme defaut pouvait envoyer un code de retrait sur la
     * mauvaise SIM.</p>
     *
     * <p>On compare maintenant l'identifiant du compte a l'abonnement ET a
     * l'ICCID, et l'on retourne null si le doute persiste — plutot que de
     * laisser le systeme choisir a notre place.</p>
     */
    @SuppressLint("MissingPermission")
    private static android.telecom.PhoneAccountHandle phoneAccountFor(Context context, int subId) {
        if (subId < 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            android.telecom.TelecomManager tcm = (android.telecom.TelecomManager)
                context.getSystemService(Context.TELECOM_SERVICE);
            if (tcm == null) return null;
            List<android.telecom.PhoneAccountHandle> accounts = tcm.getCallCapablePhoneAccounts();
            if (accounts == null || accounts.isEmpty()) return null;

            // Identifiants possibles de la SIM visee
            String cible = String.valueOf(subId);
            String iccid = null;
            try {
                SubscriptionManager sm = (SubscriptionManager)
                    context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) {
                    List<SubscriptionInfo> sims = sm.getActiveSubscriptionInfoList();
                    if (sims != null) {
                        for (SubscriptionInfo si : sims) {
                            if (si.getSubscriptionId() == subId) {
                                try {
                                    java.lang.reflect.Method m =
                                        si.getClass().getMethod("getIccId");
                                    Object v = m.invoke(si);
                                    if (v != null) iccid = String.valueOf(v);
                                } catch (Throwable ignore) { /* non accessible : on s'en passe */ }
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignore) {}

            for (android.telecom.PhoneAccountHandle h : accounts) {
                String hid = h.getId();
                if (hid == null || hid.isEmpty()) continue;
                if (hid.equals(cible)) return h;
                if (iccid != null && !iccid.isEmpty() && hid.equals(iccid)) return h;
            }
            Log.e(TAG, "aucun compte telephonique ne correspond a la SIM " + subId
                     + " — la selection par intention sera utilisee");
        } catch (Exception e) { Log.e(TAG, "phoneAccountFor: " + e.getMessage()); }
        return null;
    }

    /** Nombre de SIM actives. Sert a decider si l'ambiguite est possible. */
    @SuppressLint("MissingPermission")
    private static int compteSimsActives(Context context) {
        try {
            SubscriptionManager sm = (SubscriptionManager)
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return 1;
            List<SubscriptionInfo> sims = sm.getActiveSubscriptionInfoList();
            return (sims == null) ? 1 : Math.max(1, sims.size());
        } catch (Exception e) { return 1; }
    }

    /**
     * Determine la SIM a utiliser, avec deux tentatives.
     *
     * <p>La premiere s'appuie sur le nom de l'operateur de chaque SIM. Si ce
     * nom est absent ou inattendu — cela arrive sur certaines ROM — on se
     * rabat sur le prefixe du code USSD, qui designe l'operateur de maniere
     * tout aussi fiable (#144/#145 Orange, #111 MVola, *436/*123 Airtel).</p>
     *
     * <p>Sans ce second recours, un nom de SIM non reconnu ferait echouer des
     * retraits qui fonctionnaient auparavant.</p>
     *
     * @return le numero d'abonnement, ou -1 si vraiment indeterminable.
     */
    private static int resoudreSim(Context context, String operator, String ussdCode) {
        int subId = getSubIdForOperator(context, operator);
        if (subId >= 0) return subId;

        subId = getSubIdForOperatorFromUssd(context, ussdCode);
        if (subId >= 0) {
            Log.d(TAG, "SIM determinee par le prefixe du code USSD (nom d'operateur non reconnu)");
            return subId;
        }
        Log.e(TAG, "SIM indeterminable pour " + operator + " / " + ussdCode);
        return -1;
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
            // Nom de l'operateur, puis prefixe du code USSD en second recours
            int subId = operator != null
                ? resoudreSim(context, operator, ussdCode)
                : getSubIdForOperatorFromUssd(context, ussdCode);

            // MVola envoie le PIN dans le code : le composer sur la mauvaise
            // SIM enverrait le code secret a un autre operateur. On renonce.
            if (subId < 0 && compteSimsActives(context) > 1) {
                callback.onResult(retraitId, false,
                    "SIM " + operator + " introuvable et plusieurs SIM presentes : "
                    + "composition annulee pour ne pas viser la mauvaise carte.");
                return;
            }
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
            // Mode interactif = navigation d'ecrans. Deux cas valables :
            //  - PIN separe (Orange) : le code secret est tape a l'invite ;
            //  - sequence de menu seule (Airtel multi-etape) : le PIN fait
            //    partie de la sequence, il n'y a pas de PIN separe.
            // Refuser le second cas renverrait le retrait Airtel vers l'envoi
            // simple, qui compose le code et n'entre RIEN dans les menus.
            final boolean pinSepare = pin != null && !pin.trim().isEmpty();
            final boolean sequence  = menuReply != null && !menuReply.trim().isEmpty();
            if (!pinSepare && !sequence) {
                callback.onResult(retraitId, false,
                    "PIN et sequence de menu manquants pour le mode interactif");
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

            // Selection de la SIM : nom de l'operateur, puis prefixe du code
            int subId = resoudreSim(context, operator, ussdCode);

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
                    (subId < 0
                      ? "SIM " + operator + " introuvable sur ce telephone : composition annulee "
                      + "pour ne pas envoyer le code sur la mauvaise SIM. Verifiez que la carte "
                      + operator + " est bien inseree et active."
                      : "Impossible de composer le code USSD : aucune application Telephone par "
                      + "defaut. Reglages > Applications > Applications par defaut > Telephone."));
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
                    terminerInteractif(retraitId, maxSteps, pinSepare, callback);
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

            // ------------------------------------------------------------------
            // CONCLUSION SUR INACTIVITE, PAS SUR UN CHRONOMETRE FIXE.
            // Un delai unique calcule au depart supposait que chaque ecran
            // arrive vite. Quand l'operateur met 10 s par ecran — courant sur
            // Airtel aux heures chargees — la sequence etait coupee EN PLEIN
            // MILIEU : le desarmement tombait avant l'ecran suivant, plus rien
            // n'etait saisi, et l'operation partait en erreur alors qu'elle se
            // deroulait normalement.
            // On surveille donc la DERNIERE PROGRESSION REELLE (ecran rempli ou
            // OK d'attente clique). Tant que ca avance, on laisse faire. On ne
            // conclut que si plus rien ne bouge pendant INACTIVITE_MS, ou si la
            // duree absolue est atteinte (filet de securite).
            // ------------------------------------------------------------------
            // Chaque telephone porte UNE SEULE puce : immobiliser la session
            // USSD n'empeche aucun autre operateur de travailler. On peut donc
            // etre genereux. Airtel repond parfois tres tardivement ; couper
            // trop tot laissait l'ecran arriver APRES le desarmement, plus rien
            // n'etait saisi, et l'operation partait en erreur pour rien.
            // FIX ROBOT : le compteur d'inactivite est desormais GLISSANT cote
            // UssdAccessibilityService (armedAt repousse a chaque progression).
            // Tant que l'operateur repond, la session continue.
            final long INACTIVITE_MS = 35_000L;    // 35 s sans le moindre mouvement
            final long ABSOLU_MS     = 90_000L;    // 90 s au total (filet)
            final long debut = System.currentTimeMillis();
            final Runnable[] veille = new Runnable[1];
            veille[0] = new Runnable() {
                @Override public void run() {
                    if (conclu[0]) return;
                    long now = System.currentTimeMillis();
                    long progres = UssdAccessibilityService.getLastProgressAt();
                    if (progres <= 0) progres = debut;
                    if (now - debut >= ABSOLU_MS) {
                        Log.d(TAG, "USSD interactif : duree absolue atteinte");
                        conclure.run();
                        return;
                    }
                    if (now - progres >= INACTIVITE_MS) {
                        Log.d(TAG, "USSD interactif : plus de progression depuis "
                                + ((now - progres) / 1000) + " s");
                        conclure.run();
                        return;
                    }
                    hh.postDelayed(veille[0], 1500L);
                }
            };
            Log.d(TAG, "USSD interactif : " + maxSteps + " ecran(s), inactivite "
                    + (INACTIVITE_MS / 1000) + " s, plafond "
                    + (ABSOLU_MS / 1000) + " s");
            hh.postDelayed(veille[0], 3000L);
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

    /* ============================================================
     * CONSULTATION DE SOLDE — lecture d'ecran.
     * ------------------------------------------------------------
     * sendUssd() (one-shot) echoue des que le menu de l'operateur attend
     * encore une saisie : c'est le cas de MVola, dont la reponse se termine
     * par "0:Hiverina, 00:Pejy voalohany". Aucun texte n'est alors rendu et
     * le solde n'arrive jamais.
     *
     * On compose donc comme pour un retrait, on lit la boite affichee, puis
     * on la ferme. Aucune saisie n'est effectuee.
     * ============================================================ */
    @SuppressLint("MissingPermission")
    /**
     * true si le texte USSD contient un montant plausible (un nombre suivi
     * d'une unite monetaire Ar/MGA/Ariary/Fc, ou le mot solde/balance avec un
     * nombre). Sert a distinguer un VRAI solde d'une reponse d'erreur ou d'un
     * menu ("UNKNOWN APPLICATION", "invalid application", "Tapez 1 pour...")
     * pour lesquels la voie silencieuse est inutilisable et ou il faut basculer
     * sur la lecture d'ecran.
     */
    private static boolean soldeExploitable(String txt) {
        if (txt == null) return false;
        String t = txt.trim();
        if (t.isEmpty()) return false;
        String bas = t.toLowerCase(java.util.Locale.ROOT);
        // Rejets explicites : reponses d'erreur connues, sans montant reel.
        if (bas.contains("unknown application") || bas.contains("invalid")
                || bas.contains("not available") || bas.contains("try again")
                || bas.contains("service unavailable") || bas.contains("indisponible")) {
            return false;
        }
        // Au moins un nombre accompagne d'une unite monetaire.
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(\\d[\\d\\s.,]{1,})\\s*(ar|mga|ariary|fc)",
                     java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(t);
        if (m.find()) return true;
        // Repli : "solde"/"balance" accompagne d'un nombre d'au moins 2 chiffres.
        if ((bas.contains("solde") || bas.contains("balance"))
                && java.util.regex.Pattern.compile("\\d{2,}").matcher(t).find()) {
            return true;
        }
        return false;
    }

    public static void lireSoldeUssd(Context context, String reference,
                                     String ussdCode, String operator,
                                     UssdCallback callback) {
        // ------------------------------------------------------------------
        // D'ABORD : la voie SILENCIEUSE.
        // ------------------------------------------------------------------
        // sendUssdRequest() interroge l'operateur sans afficher la moindre
        // boite de dialogue : le solde remonte directement, l'ecran du
        // telephone ne bouge pas. C'est le comportement souhaite.
        //
        // Elle ne fonctionne que si la reponse CLOT la session. Certains menus
        // en attendent encore une saisie ("Tapez 1 pour recevoir le solde par
        // SMS") : dans ce cas seulement, on retombe sur la lecture d'ecran,
        // avec une boite qui apparait brievement.
        // ------------------------------------------------------------------
        // Code multi-etape (ex. Airtel "*436#|6|2|2011") : la voie silencieuse
        // composerait le code ENTIER, separateurs compris — l'operateur rejette.
        // Ces codes exigent une navigation dans le menu : lecture d'ecran directe.
        if (ussdCode != null && ussdCode.indexOf('|') >= 0) {
            Log.d(TAG, "code solde multi-etape — lecture d'ecran directe");
            lireSoldeParEcran(context, reference, ussdCode, operator, callback);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final boolean[] repondu = { false };
            try {
                int subId = resoudreSim(context, operator, ussdCode);
                if (subId < 0 && compteSimsActives(context) > 1) {
                    callback.onResult(reference, false,
                        "SIM " + operator + " introuvable : lecture annulee.");
                    return;
                }
                TelephonyManager baseTm = (TelephonyManager)
                    context.getSystemService(Context.TELEPHONY_SERVICE);
                if (baseTm != null) {
                    TelephonyManager tm = subId >= 0
                        ? baseTm.createForSubscriptionId(subId) : baseTm;
                    tm.sendUssdRequest(ussdCode,
                        new TelephonyManager.UssdResponseCallback() {
                            @Override
                            public void onReceiveUssdResponse(TelephonyManager t,
                                                              String req, CharSequence msg) {
                                if (repondu[0]) return;
                                repondu[0] = true;
                                String txt = msg == null ? "" : msg.toString().trim();
                                // La voie silencieuse ne convient QUE si l'operateur
                                // renvoie un vrai solde. Sur les menus qui attendent
                                // une saisie (Orange #144, MVola #111), la reponse est
                                // vide, ou un texte d'erreur ("UNKNOWN APPLICATION",
                                // "invalid"), ou le menu lui-meme : aucun montant. Sans
                                // ce controle, ce texte etait renvoye comme un solde et
                                // enregistre comme faux solde cote serveur. On bascule
                                // alors sur la lecture d'ecran (menu interactif).
                                if (soldeExploitable(txt)) {
                                    Log.d(TAG, "solde lu sans affichage pour " + operator);
                                    callback.onResult(reference, true, txt);
                                } else {
                                    Log.d(TAG, "voie silencieuse sans montant exploitable ("
                                             + (txt.length() > 40 ? txt.substring(0, 40) : txt)
                                             + ") — passage par la lecture d'ecran");
                                    lireSoldeParEcran(context, reference, ussdCode, operator, callback);
                                }
                            }
                            @Override
                            public void onReceiveUssdResponseFailed(TelephonyManager t,
                                                                    String req, int code) {
                                if (repondu[0]) return;
                                repondu[0] = true;
                                // Session non close : on passe a la lecture d'ecran.
                                Log.d(TAG, "voie silencieuse indisponible (" + code
                                         + ") — passage par la lecture d'ecran");
                                lireSoldeParEcran(context, reference, ussdCode, operator, callback);
                            }
                        }, new android.os.Handler(android.os.Looper.getMainLooper()));

                    // Garde-fou : si l'operateur ne rappelle jamais, basculer.
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (repondu[0]) return;
                        repondu[0] = true;
                        Log.d(TAG, "voie silencieuse muette — passage par la lecture d'ecran");
                        lireSoldeParEcran(context, reference, ussdCode, operator, callback);
                    }, 15_000L);
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "voie silencieuse: " + e.getMessage());
                if (repondu[0]) return;
                repondu[0] = true;
            }
        }
        lireSoldeParEcran(context, reference, ussdCode, operator, callback);
    }

    /** Lecture d'ecran : une boite apparait brievement, puis est fermee. */
    @SuppressLint("MissingPermission")
    private static void lireSoldeParEcran(Context context, String reference,
                                          String ussdCode, String operator,
                                          UssdCallback callback) {
        try {
            if (!UssdAccessibilityService.isEnabled(context)) {
                callback.onResult(reference, false,
                    "Service d'accessibilite MATULMADA desactive : impossible de lire le solde.");
                return;
            }
            // Multi-etape : un code solde avec '|' (ex: "*436#|6|2|2011") = dial
            // + sequence a taper avant lecture. On ne compose que le 1er element.
            String dialCode = ussdCode;
            String menuSeq  = "";
            int    maxSeq   = 0;
            if (ussdCode != null && ussdCode.indexOf('|') >= 0) {
                String[] partsS = ussdCode.split("\\|");
                java.util.List<String> cleanS = new java.util.ArrayList<>();
                for (String x : partsS) { if (x != null && !x.trim().isEmpty()) cleanS.add(x.trim()); }
                if (cleanS.size() >= 2) {
                    dialCode = cleanS.get(0);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < cleanS.size(); i++) {
                        if (sb.length() > 0) sb.append('|');
                        sb.append(cleanS.get(i));
                    }
                    menuSeq = sb.toString();
                    maxSeq  = cleanS.size() - 1;
                }
            }
            if (!UssdAccessibilityService.armLecture(reference, menuSeq, maxSeq)) {
                callback.onResult(reference, false,
                    "Une autre operation USSD est en cours sur ce telephone.");
                return;
            }

            int subId = resoudreSim(context, operator, dialCode);
            if (!composerUssd(context, dialCode, subId)) {
                UssdAccessibilityService.disarm();
                callback.onResult(reference, false,
                    "Impossible de composer le code : SIM " + operator + " introuvable "
                    + "ou aucune application Telephone par defaut.");
                return;
            }
            Log.d(TAG, "lecture solde " + operator + " composee");

            final android.os.Handler hh =
                new android.os.Handler(android.os.Looper.getMainLooper());
            final boolean[] conclu = { false };

            final Runnable conclure = () -> {
                if (conclu[0]) return;
                conclu[0] = true;
                hh.removeCallbacksAndMessages(null);
                // Uniquement le texte lu PENDANT cette consultation.
                // Se rabattre sur getLastDialogText() reprenait le dernier
                // ecran vu, qui pouvait appartenir a l'operateur precedent :
                // le solde d'Orange se retrouvait alors enregistre comme
                // celui de MVola.
                String texte = UssdAccessibilityService.getTexteLu();
                UssdAccessibilityService.disarm();
                boolean ok = texte != null && !texte.trim().isEmpty();
                Log.d(TAG, "lecture solde terminee ok=" + ok);
                callback.onResult(reference, ok, ok ? texte
                    : "Aucune boite USSD lisible. Verifiez l'application Telephone par defaut, "
                    + "le service d'accessibilite et l'affichage par-dessus les autres applications.");
            };

            final Runnable sonde = new Runnable() {
                @Override public void run() {
                    if (conclu[0]) return;
                    if (UssdAccessibilityService.lectureTerminee()) {
                        hh.postDelayed(conclure, 800L);
                        return;
                    }
                    hh.postDelayed(this, 1000L);
                }
            };
            hh.postDelayed(sonde, 1500L);

            // Meme principe qu'un retrait : un code de solde multi-etape
            // enchaine plusieurs ecrans, et l'operateur peut etre lent. Un
            // forfait de 20 s coupait la lecture avant le dernier ecran.
            // FIX ROBOT : une consultation de solde ne doit jamais monopoliser
            // la ligne au detriment d'un retrait client.
            final long L_INACTIVITE_MS = 25_000L;   // 25 s sans mouvement
            final long L_ABSOLU_MS     = 60_000L;   // 60 s au total (filet)
            final long lDebut = System.currentTimeMillis();
            final Runnable[] lVeille = new Runnable[1];
            lVeille[0] = new Runnable() {
                @Override public void run() {
                    if (conclu[0]) return;
                    long now = System.currentTimeMillis();
                    long progres = UssdAccessibilityService.getLastProgressAt();
                    if (progres <= 0) progres = lDebut;
                    if (now - lDebut >= L_ABSOLU_MS
                            || now - progres >= L_INACTIVITE_MS) {
                        Log.d(TAG, "lecture solde : plus de progression, on conclut");
                        conclure.run();
                        return;
                    }
                    hh.postDelayed(lVeille[0], 1500L);
                }
            };
            hh.postDelayed(lVeille[0], 3000L);

        } catch (Exception e) {
            UssdAccessibilityService.disarm();
            Log.e(TAG, "lireSoldeUssd: " + e.getMessage());
            callback.onResult(reference, false, "Erreur lecture solde: " + e.getMessage());
        }
    }

    /** Construit le compte rendu final d'un envoi interactif. */
    private static void terminerInteractif(String retraitId, int maxSteps,
                                           boolean pinSepare, UssdCallback callback) {
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

        // Succes provisoire : PIN tape (Orange), ou — quand le PIN fait partie
        // de la sequence de menu (Airtel multi-etape) — tous les ecrans remplis.
        boolean ok = pinSepare ? pinTape : (etapes >= maxSteps && maxSteps > 0);
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
        } else if (!pinSepare && !ok) {
            motif = "Seulement " + etapes + " ecran(s) sur " + maxSteps
                  + " ont ete remplis : sequence de menu incomplete.";
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
                        // Meme correction que dans phoneAccountFor : l'identifiant
                        // du compte peut etre l'ICCID et non le numero d'abonnement.
                        android.telecom.PhoneAccountHandle h2 = phoneAccountFor(context, subId);
                        if (h2 != null) {
                            intent.putExtra(
                                android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h2);
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
            if (subId < 0 && compteSimsActives(context) > 1) {
                callback.onResult(operator, false,
                    "SIM " + operator + " introuvable : lecture annulee.");
                return;
            }
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
