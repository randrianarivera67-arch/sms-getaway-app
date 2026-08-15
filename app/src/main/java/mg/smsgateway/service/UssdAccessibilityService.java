package mg.smsgateway.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Saisit automatiquement le code PIN dans la boite de dialogue USSD de l'operateur.
 *
 * POURQUOI CE SERVICE EST NECESSAIRE
 * ----------------------------------
 * TelephonyManager.sendUssdRequest() est une API "one-shot" : elle envoie un code
 * et recoit UNE reponse. Elle ne sait pas repondre a une invite USSD du type
 * "Ampidiro ny kaody miafina" (Orange). Or Orange refuse un code USSD contenant
 * deja le PIN : il faut le taper dans la boite de dialogue affichee par le systeme.
 *
 * Ce service surveille l'apparition de cette boite, y ecrit le PIN, puis appuie
 * sur "Envoyer". C'est la seule methode fiable sous Android pour un USSD interactif.
 *
 * SECURITE
 * --------
 * - Le PIN n'est jamais journalise (masque dans les logs).
 * - Le service n'agit QUE s'il a ete "arme" juste avant par le GatewayService,
 *   et seulement pendant une fenetre de temps courte (ARM_TIMEOUT_MS).
 * - Une seule saisie par armement : impossible de rejouer un PIN.
 *
 * COMPATIBILITE
 * -------------
 * Fonctionne d'Android 5.0 (API 21) a Android 15 (API 35). Les libelles de bouton
 * et les identifiants de vue varient selon les constructeurs (stock, Samsung,
 * Xiaomi/MIUI, Oppo/Realme, Huawei, Transsion) : la detection combine plusieurs
 * strategies (viewId, libelle multilingue, position, type de noeud).
 */
public class UssdAccessibilityService extends AccessibilityService {

    private static final String TAG = "UssdAccess";

    /** Duree pendant laquelle un PIN arme reste valable. */
    private static final long ARM_TIMEOUT_MS = 90_000L;

    /** Anti-rebond : evite de re-traiter la meme boite plusieurs fois. */
    private static final long MIN_ACTION_INTERVAL_MS = 800L;

    // ---- Etat partage (arme par UssdQueue avant l'envoi du code USSD) ----
    // UN SEUL retrait peut etre arme a la fois : UssdQueue le garantit en
    // n'executant jamais deux retraits en parallele. arm() refuse tout de meme
    // un second armement, par securite.
    private static volatile String  armedPin      = null;
    /** Reponse a taper sur un ecran de saisie qui n'est PAS une demande de PIN. */
    private static volatile String  armedMenuReply = "";
    /** Nombre maximum d'ecrans de saisie a traiter (Orange en demande 2). */
    private static volatile int     armedMaxSteps  = 1;
    /** Nombre d'ecrans reellement traites. */
    private static volatile int     stepsDone      = 0;
    /** Multi-etape : index de la reponse courante dans la sequence menuReply
     *  (separee par '|'). Avance a chaque ecran menu valide. */
    private static volatile int     menuReplyIndex = 0;
    // Ecrans d'attente ("tsindrio ny ok") : suivi SEPARE du reste. Le meme texte
    // peut reapparaitre plusieurs fois dans une sequence (une fois par choix de
    // menu) : une simple comparaison de texte le prendrait pour "deja traite" et
    // la sequence resterait bloquee. On autorise donc un nouveau clic passe un
    // court delai, ce qui evite aussi de cliquer en rafale sur la meme boite.
    private static volatile String  lastAttenteSignature = "";
    private static volatile long    lastAttenteAt = 0L;
    private static final long       ATTENTE_REPEAT_MS = 3000L;
    /** Texte du dernier ecran de saisie qu'on n'a PAS su remplir (diagnostic). */
    private static volatile String  ecranNonTraite = "";
    private static volatile long    armedAt       = 0L;
    private static volatile String  armedRetraitId = null;
    private static volatile String  lastDialogText = "";
    /**
     * Texte lu APRES la saisie validee. Sans lui on remonte au serveur l'ecran
     * "Ampidiro ny kaody miafina" lui-meme, et le serveur ne peut pas savoir si
     * le PIN a ete tape ou non.
     */
    private static volatile String  postSubmitText = "";
    private static volatile boolean pinSubmitted   = false;
    /** true des qu'un ecran confirme que le transfert est parti chez l'operateur. */
    private static volatile boolean transactionInitiee = false;
    /** true des qu'un ecran annonce un echec definitif de l'operateur. */
    private static volatile boolean transactionEchouee = false;

    /** Signature du dernier ecran auquel on a repondu : evite la double reponse. */
    private static volatile String lastHandledSignature = "";

    private long lastActionAt = 0L;

    /**
     * Motifs indiquant qu'un ecran demande le CODE SECRET (mg / fr / en).
     * Sert a garantir que le PIN n'est jamais tape dans un champ de menu.
     */
    private static final String[] PIN_PROMPTS = {
            "kaody miafina",     // mg : "Ampidiro ny kaody miafina"
            "code secret",       // fr : "entrez votre code secret"
            "code pin", "votre pin", "code confidentiel",
            "mot de passe",
            "enter your pin", "enter pin", "secret code"
    };

    /**
     * Ecrans signifiant que le transfert est DEJA PARTI chez l'operateur.
     * Cas reel Orange Money : apres la saisie du PIN, une derniere boite
     * s'affiche — "Transfert initie. Vous allez recevoir une confirmation par
     * SMS. 1: enregistrer le numero ... 2: ne pas enregistrer ..." — avec un
     * champ de saisie. Ce n'est PAS une etape de la transaction : elle est
     * terminee. Ce menu ne sert qu'au repertoire telephonique.
     * On ferme donc la session par ANNULER, sans rien saisir, et on enchaine
     * sur le retrait suivant.
     */
    private static final String[] FIN_TRANSACTION = {
            // --- Orange Money ---
            "transfert initie", "transfert initi",
            "vous allez recevoir une confirmation",
            "est reussi", "est réussi",
            // --- MVola / Telma (releve sur telephone) ---
            // "Votre transaction a reussi, pour enregistrer 0380990983 dans
            //  votre repertoire MVola, Entrer le nom correspondant ou ignorer :"
            "transaction a reussi", "transaction a réussi",
            "repertoire mvola", "répertoire mvola",
            // --- commun ---
            "transaction en cours",
            "nahomby", "vita soa aman-tsara"
    };

    /**
     * Formulations d'ECHEC contenant un mot de succes ("n'a pas reussi").
     * Testees en premier : sans cela on fermerait la boite par ANNULER en
     * croyant la transaction partie, alors qu'elle a echoue.
     * NE JAMAIS y mettre "annul" : les libelles des boutons font partie du
     * texte lu et provoqueraient un faux echec sur tous les ecrans.
     */
    /**
     * Ecrans annoncant un ECHEC DEFINITIF de l'operateur. Cas reel MVola :
     * "Votre solde MVola est insuffisant. Votre solde est de 5 692Ar. Faites un
     *  depot MVola suffisant pour pouvoir effectuer cette transaction. Ref:..."
     * avec un SEUL bouton OK et AUCUN champ de saisie.
     *
     * Sans ce traitement, la boite restait affichee : le service ne trouvait pas
     * de champ a remplir et ne faisait rien. On attendait alors le delai complet
     * (25 s) pour conclure, et la boite pouvait genait le retrait suivant.
     * On la ferme donc immediatement et on conclut sans attendre.
     */
    private static final String[] ECHEC_TERMINAL = {
            // --- francais ---
            "insuffisant",
            "code secret incorrect", "code incorrect", "code errone",
            "numero incorrect", "numero invalide", "numero inconnu",
            "transaction impossible", "operation impossible",
            "service indisponible", "reessayez plus tard", "reessayer plus tard",
            "montant invalide", "montant incorrect",
            "compte bloque", "compte suspendu",
            "une erreur", "erreur est survenue",
            // --- anglais : Orange repond en anglais sur certaines erreurs ---
            // "An error occurred while processing your request. We will be
            //  solving it shortly. Please try again later."
            "an error occurred", "error occurred", "error while processing",
            "ihm non valide", "code ihm", "de connexion", "unknown application", "application inconnue",
            "try again later", "please try again",
            "insufficient", "invalid", "incorrect",
            "service unavailable", "temporarily unavailable",
            // --- malgache ---
            "tsy ampy", "kaody diso", "tsy mety", "andramo indray"
    };

    private static boolean echecTerminal(String texte) {
        if (TextUtils.isEmpty(texte)) return false;
        String t = texte.toLowerCase(Locale.ROOT);
        for (String m : ECHEC_TERMINAL) if (t.contains(m)) return true;
        return false;
    }

    private static final String[] ECHEC_MALGRE_MOT_POSITIF = {
            "n'a pas reussi", "na pas reussi", "pas reussi", "pas réussi",
            "non reussi", "non réussi",
            "echoue", "échoué", "echec", "échec",
            "tsy nahomby"
    };

    private static boolean transactionDejaPartie(String texte) {
        if (TextUtils.isEmpty(texte)) return false;
        String t = texte.toLowerCase(Locale.ROOT);
        for (String m : ECHEC_MALGRE_MOT_POSITIF) if (t.contains(m)) return false;
        for (String m : FIN_TRANSACTION) if (t.contains(m)) return true;
        return false;
    }

    private static boolean ressembleADemandeDePin(String texte) {
        if (TextUtils.isEmpty(texte)) return false;
        String t = texte.toLowerCase(Locale.ROOT);
        for (String m : PIN_PROMPTS) if (t.contains(m)) return true;
        return false;
    }

    /**
     * Arme le service : le prochain dialogue USSD demandant une saisie recevra ce PIN.
     * Appele juste AVANT l'envoi du code USSD.
     */
    public static void arm(String pin, String retraitId) {
        arm(pin, "", 1, retraitId);
    }

    /**
     * @param menuReply reponse a taper sur un ecran de saisie qui n'est PAS une
     *                  demande de PIN (menu de confirmation). Vide = ne rien taper.
     * @param maxSteps  nombre maximum d'ecrans de saisie a traiter.
     *                  Orange Money en demande 2, MVola 1.
     * @return false si un autre retrait est deja arme (refus, jamais d'ecrasement).
     */
    public static synchronized boolean arm(String pin, String menuReply,
                                           int maxSteps, String retraitId) {
        // ----------------------------------------------------------------
        // GARDE-FOU ARGENT : ne JAMAIS ecraser un armement en cours.
        // L'etat est statique et global ; armer le retrait B pendant que A est
        // en cours ferait taper le PIN de B dans la boite de A, et attribuerait
        // le texte de B au resultat de A.
        // ----------------------------------------------------------------
        if (isArmed() && armedRetraitId != null && !armedRetraitId.equals(retraitId)) {
            Log.e(TAG, "REFUS d'armer " + retraitId + " : " + armedRetraitId + " est deja en cours");
            return false;
        }
        armedPin       = (pin == null) ? null : pin.trim();
        armedMenuReply = (menuReply == null) ? "" : menuReply.trim();
        armedMaxSteps  = maxSteps < 1 ? 1 : maxSteps;
        stepsDone      = 0;
        menuReplyIndex = 0;
        lastAttenteSignature = "";
        lastAttenteAt = 0L;
        attenteClics = 0;
        ecranNonTraite = "";
        lastHandledSignature = "";
        armedRetraitId = retraitId;
        armedAt        = System.currentTimeMillis();
        lastDialogText = "";
        postSubmitText = "";
        pinSubmitted   = false;
        transactionInitiee = false;
        transactionEchouee = false;
        Log.d(TAG, "arme pour retrait=" + retraitId + " (pin masque, "
                + (armedPin == null ? 0 : armedPin.length()) + " chiffres, max "
                + armedMaxSteps + " ecran(s))");
        return true;
    }

    /** Nombre d'ecrans de saisie reellement remplis lors du dernier envoi. */
    public static int getStepsDone() { return stepsDone; }

    /** true si un ecran a confirme que le transfert etait parti chez l'operateur. */
    public static boolean wasTransactionInitiee() { return transactionInitiee; }

    /** true si l'operateur a annonce un echec definitif (solde insuffisant, etc.). */
    public static boolean wasTransactionEchouee() { return transactionEchouee; }

    /** true des qu'une conclusion est possible : plus la peine d'attendre. */
    public static boolean estConclu() {
        return transactionInitiee || transactionEchouee || lectureFaite;
    }

    /* ============================================================
     * MODE LECTURE SEULE — consultation de solde.
     * ------------------------------------------------------------
     * Certains menus (MVola notamment) repondent par un ecran qui attend
     * encore une saisie ("0:Hiverina, 00:Pejy voalohany"). L'API one-shot
     * sendUssdRequest() considere alors la session comme echouee et ne rend
     * AUCUN texte : le solde n'arrive jamais et l'affichage reste en
     * chargement indefiniment.
     *
     * En mode lecture on compose le code, on LIT la boite, puis on la ferme.
     * On ne saisit jamais rien : aucune transaction n'est en cours, il n'y a
     * donc rien a valider par megarde.
     * ============================================================ */
    private static volatile boolean modeLecture  = false;
    private static volatile boolean lectureFaite = false;
    private static volatile String  texteLu      = "";

    /** Arme une simple lecture d'ecran (consultation de solde). */
    public static synchronized boolean armLecture(String reference) {
        return armLecture(reference, "", 0);
    }

    // Multi-etape : menuReply = sequence "6|2|2011" tapee ecran par ecran AVANT
    // la lecture du solde ; maxSteps = nombre d'ecrans a saisir. Vide/0 = lecture
    // directe (solde des le 1er ecran, ex: MVola).
    public static synchronized boolean armLecture(String reference, String menuReply, int maxSteps) {
        if (isArmed() && armedRetraitId != null && !armedRetraitId.equals(reference)) {
            Log.e(TAG, "REFUS de lecture " + reference + " : " + armedRetraitId + " en cours");
            return false;
        }
        armedPin       = null;
        armedMenuReply = (menuReply == null) ? "" : menuReply;
        armedMaxSteps  = maxSteps;
        stepsDone      = 0;
        menuReplyIndex = 0;
        lastAttenteSignature = "";
        lastAttenteAt = 0L;
        attenteClics = 0;
        ecranNonTraite = "";
        lastHandledSignature = "";
        armedRetraitId = reference;
        armedAt        = System.currentTimeMillis();
        lastDialogText = "";
        postSubmitText = "";
        pinSubmitted   = false;
        transactionInitiee = false;
        transactionEchouee = false;
        modeLecture    = true;
        lectureFaite   = false;
        texteLu        = "";
        Log.d(TAG, "arme en LECTURE pour " + reference);
        return true;
    }

    public static boolean lectureTerminee() { return lectureFaite; }
    public static String  getTexteLu()      { return texteLu; }

    /** Texte du dernier ecran de saisie non reconnu (vide si tout s'est bien passe). */
    public static String getEcranNonTraite() { return ecranNonTraite; }

    /** Desarme immediatement (fin de transaction ou annulation). */
    public static void disarm() {
        modeLecture = false;
        armedPin = null;
        armedRetraitId = null;
        armedAt = 0L;
    }

    /** true si le PIN a effectivement ete saisi et valide depuis le dernier arm(). */
    public static boolean wasPinSubmitted() { return pinSubmitted; }

    /** Dernier texte lu dans une boite de dialogue USSD (pour le compte rendu serveur). */
    public static String getLastDialogText() { return lastDialogText; }

    /** Texte le plus pertinent pour le serveur : celui d'APRES la saisie si on l'a. */
    public static String getReportText() {
        if (postSubmitText != null && !postSubmitText.trim().isEmpty()) return postSubmitText;
        return lastDialogText;
    }

    /** true si l'utilisateur a active ce service dans les reglages d'accessibilite. */
    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;
            String target = ctx.getPackageName() + "/" + UssdAccessibilityService.class.getName();
            String shortTarget = ctx.getPackageName() + "/.service.UssdAccessibilityService";
            for (String part : enabled.split(":")) {
                String p = part.trim();
                if (p.equalsIgnoreCase(target) || p.equalsIgnoreCase(shortTarget)) return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "isEnabled: " + e.getMessage());
        }
        return false;
    }

    private static boolean isArmed() {
        if ((System.currentTimeMillis() - armedAt) >= ARM_TIMEOUT_MS) return false;
        if (modeLecture) return !lectureFaite;
        return armedPin != null && !armedPin.isEmpty();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        final int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return;
        }
        if (root == null) return;

        try {
            if (!looksLikeUssdDialog(root, event)) return;

            String text = collectText(root);
            if (!TextUtils.isEmpty(text)) {
                lastDialogText = text;
                // Ecran vu APRES la validation : c'est celui qui dit reellement
                // ce qu'a repondu l'operateur.
                if (pinSubmitted) postSubmitText = text;
            }

            if (!isArmed()) {
                // ------------------------------------------------------------
                // BALAYAGE : boite USSD restee ouverte alors qu'aucune operation
                // n'est en cours (fin de session Telma/Orange, "Merci d'avoir
                // utiliser ce service", resultat arrive apres le desarmement...).
                // Non fermees, elles S'EMPILENT — dix boites superposees vues sur
                // Telma — et la suivante s'ouvre derriere, hors de portee.
                // On ne ferme QUE les boites SANS champ de saisie : une boite qui
                // attend une reponse n'est jamais touchee hors operation armee.
                // ------------------------------------------------------------
                try {
                    if (findEditable(root) == null && peutCliquerAttente(text)) {
                        Log.d(TAG, "boite USSD orpheline -> fermeture");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                AccessibilityNodeInfo rO = getRootInActiveWindow();
                                if (rO != null) clickDismissButton(rO);
                            } catch (Exception e) {
                                Log.e(TAG, "fermeture orpheline: " + e.getMessage());
                            }
                        }, 400L);
                    }
                } catch (Exception e) { /* le balayage ne doit rien casser */ }
                return;
            }

            // ----------------------------------------------------------------
            // MODE LECTURE : relever le texte affiche puis fermer la boite.
            // Aucune saisie, quel que soit le contenu de l'ecran.
            // ----------------------------------------------------------------
            if (modeLecture) {
                // Multi-etape : tant que la sequence (armedMenuReply="6|2|2011")
                // n'est pas epuisee, on tape POSITIONNELLEMENT la reponse courante
                // sur chaque ecran de saisie. Le solde n'est lu qu'ensuite.
                if (!armedMenuReply.isEmpty()) {
                    String[] _repL = armedMenuReply.split("\\|");
                    if (menuReplyIndex < _repL.length) {
                        long nowL = System.currentTimeMillis();
                        if (nowL - lastActionAt < MIN_ACTION_INTERVAL_MS) return;
                        AccessibilityNodeInfo editL = findEditable(root);
                        if (editL == null) {
                            // Ecran d'attente Airtel ("tsindrio ny ok") : le valider
                            // pour que le menu suivant s'affiche. Aucune reponse de
                            // la sequence n'est consommee ici.
                            if (ecranDattente(text)) {
                                if (!peutCliquerAttente(text)) return;
                                lastActionAt = nowL;
                                Log.d(TAG, "lecture: ecran d'attente -> clic OK");
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    try {
                                        AccessibilityNodeInfo rW = getRootInActiveWindow();
                                        if (rW != null) clickSendButton(rW);
                                    } catch (Exception e) {
                                        Log.e(TAG, "clic OK attente (lecture): " + e.getMessage());
                                    }
                                }, 250L);
                            }
                            return;                       // ecran sans saisie : patienter
                        }
                        String sigL = TextUtils.isEmpty(text) ? "<vide>" : text;
                        if (sigL.equals(lastHandledSignature)) return;
                        String valL = _repL[menuReplyIndex];
                        lastActionAt = nowL;
                        final String sigL2 = sigL;
                        // Meme ecriture VERIFIEE que pour un retrait : sans elle,
                        // un ecran encore en cours d'affichage recevait un envoi
                        // a vide et la consultation restait bloquee.
                        ecrireEtValider(valL, 0, () -> {
                            menuReplyIndex++;
                            lastHandledSignature = sigL2;
                            Log.d(TAG, "lecture: ecran menu valide (" + menuReplyIndex
                                    + "/" + _repL.length + ")");
                        });
                        return;                               // pas encore la lecture
                    }
                }
                if (!TextUtils.isEmpty(text) && !lectureFaite) {
                    // Un ecran d'attente n'est PAS le solde : le prendre pour tel
                    // enregistrerait "Eo ampanatontosana ny fangatahana" comme
                    // montant. On le valide et on attend le vrai resultat.
                    if (ecranDattente(text)) {
                        if (!peutCliquerAttente(text)) return;
                        lastActionAt = System.currentTimeMillis();
                        Log.d(TAG, "lecture: attente avant resultat -> clic OK");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                AccessibilityNodeInfo rF = getRootInActiveWindow();
                                if (rF != null) clickSendButton(rF);
                            } catch (Exception e) {
                                Log.e(TAG, "clic OK attente finale: " + e.getMessage());
                            }
                        }, 250L);
                        return;
                    }
                    texteLu      = text;
                    lectureFaite = true;
                    Log.d(TAG, "lecture solde effectuee pour " + armedRetraitId);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            AccessibilityNodeInfo r5 = getRootInActiveWindow();
                            // La boite de resultat ("Ny toe bolanao dia Ar ...
                            // Trans ID: ...") n'a qu'un bouton OK. clickDismissButton
                            // essaie button1 (OK) en premier ; si rien n'est
                            // trouve, on retente explicitement le bouton positif.
                            // Sans cette fermeture, la boite reste a l'ecran et la
                            // consultation suivante s'ouvre derriere elle.
                            if (r5 != null && !clickDismissButton(r5)) {
                                clickSendButton(r5);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "fermeture lecture: " + e.getMessage());
                        }
                    }, 300L);
                    // Deuxieme passage : sur certains telephones le premier clic
                    // arrive avant que la boite soit pleinement affichee.
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            AccessibilityNodeInfo r7 = getRootInActiveWindow();
                            if (r7 != null && looksLikeUssdDialog(r7, null)
                                    && findEditable(r7) == null) {
                                Log.d(TAG, "boite de solde encore ouverte -> second clic OK");
                                if (!clickDismissButton(r7)) clickSendButton(r7);
                            }
                        } catch (Exception e) { /* second essai facultatif */ }
                    }, 1200L);
                }
                return;
            }

            // ----------------------------------------------------------------
            // PRIORITE 1 : l'ecran annonce que le transfert est DEJA PARTI.
            // Cas Orange Money : "Transfert initie. Vous allez recevoir une
            // confirmation par SMS. 1: enregistrer le numero ... 2: ne pas ..."
            // Cet ecran a un champ de saisie, mais repondre n'a AUCUN effet sur
            // l'argent : la transaction est close. On ferme par ANNULER pour
            // liberer la SIM et enchainer immediatement le retrait suivant.
            // Ce test passe AVANT la logique de saisie, sinon on tomberait dans
            // "ecran non reconnu" et le retrait serait declare en echec alors
            // que le client a bien recu son argent.
            // ----------------------------------------------------------------
            // ----------------------------------------------------------------
            // PRIORITE 0 : echec definitif annonce par l'operateur.
            // Cette boite n'a PAS de champ de saisie (bouton OK seul) : sans ce
            // traitement, le service n'y touchait pas, elle restait affichee, et
            // on attendait le delai complet avant de conclure. On la ferme et on
            // conclut tout de suite : le retrait suivant peut demarrer.
            // ----------------------------------------------------------------
            if (echecTerminal(text)) {
                if (!transactionEchouee) {
                    transactionEchouee = true;
                    postSubmitText = text;
                    Log.d(TAG, "echec operateur pour retrait=" + armedRetraitId
                            + " -> fermeture de la boite");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            AccessibilityNodeInfo r4 = getRootInActiveWindow();
                            if (r4 != null && !clickDismissButton(r4)) {
                                Log.d(TAG, "bouton de fermeture introuvable, la boite se fermera seule");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "clic fermeture: " + e.getMessage());
                        }
                    }, 300L);
                }
                return;
            }

            if (transactionDejaPartie(text)) {
                if (!transactionInitiee) {
                    transactionInitiee = true;
                    postSubmitText = text;
                    Log.d(TAG, "transfert parti chez l'operateur pour retrait=" + armedRetraitId
                            + " -> fermeture par ANNULER");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            AccessibilityNodeInfo r3 = getRootInActiveWindow();
                            if (r3 != null && !clickCancelButton(r3)) {
                                Log.d(TAG, "bouton ANNULER introuvable, la boite se fermera seule");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "clic annuler: " + e.getMessage());
                        }
                    }, 300L);
                }
                return;
            }

            if (stepsDone >= armedMaxSteps) return;   // quota d'ecrans atteint

            long now = System.currentTimeMillis();
            if (now - lastActionAt < MIN_ACTION_INTERVAL_MS) return;

            AccessibilityNodeInfo input = findEditable(root);
            if (input == null) {
                // Boite SANS saisie : soit un ecran d'attente ("tsindrio ny ok"),
                // qu'il faut valider pour que la session continue, soit un ecran
                // final traite plus haut. On ne clique QUE sur l'ecran d'attente
                // reconnu, jamais a l'aveugle : cliquer sur une boite inconnue
                // pourrait valider une operation non voulue.
                if (ecranDattente(text)) {
                    if (!peutCliquerAttente(text)) return;   // clic deja emis a l'instant
                    lastActionAt = System.currentTimeMillis();
                    Log.d(TAG, "ecran d'attente operateur -> clic OK (aucune etape consommee)");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            AccessibilityNodeInfo rA = getRootInActiveWindow();
                            if (rA != null) clickSendButton(rA);
                        } catch (Exception e) {
                            Log.e(TAG, "clic OK attente: " + e.getMessage());
                        }
                    }, 250L);
                }
                return;                             // rien d'autre a faire ici
            }

            // ----------------------------------------------------------------
            // Ne JAMAIS repondre deux fois au meme ecran.
            // Orange Money demande DEUX saisies successives : sans cette garde,
            // un evenement redondant consommerait la seconde etape sur le
            // premier ecran, et la vraie seconde boite resterait sans reponse.
            // ----------------------------------------------------------------
            String signature = TextUtils.isEmpty(text) ? "<vide>" : text;
            if (signature.equals(lastHandledSignature)) return;

            // ----------------------------------------------------------------
            // CHOIX DE LA VALEUR — pilote par le CONTENU de l'ecran, jamais par
            // un simple compteur. Le PIN n'est tape que sur un ecran qui demande
            // effectivement le code secret ; tout autre ecran de saisie recoit
            // la reponse de menu configuree. Ainsi, meme si l'ordre des ecrans
            // change chez l'operateur, le PIN ne part jamais dans un champ de menu.
            // ----------------------------------------------------------------
            final boolean demandePin = ressembleADemandeDePin(text);
            // Un PIN separe n'existe que pour Orange. Sur Airtel, le code secret
            // est une ETAPE de la sequence ({pin} en etape 7) : armedPin est vide.
            // Sans ce controle, l'ecran "Code secret" recevait armedPin vide, rien
            // n'etait tape, et le retrait restait fige sur cet ecran.
            final boolean pinSepareArme = armedPin != null && !armedPin.isEmpty();
            final boolean utilisePinArme = demandePin && pinSepareArme;
            final String value;
            if (utilisePinArme) {
                value = armedPin;
            } else if (!armedMenuReply.isEmpty()) {
                // Multi-etape : armedMenuReply peut contenir une SEQUENCE separee
                // par '|' (ex: "2|1|1|033...|10000|2|1234"). On tape l'element
                // courant ; l'index avance apres chaque ecran valide.
                String[] _rep = armedMenuReply.split("\\|");
                if (menuReplyIndex >= _rep.length) return; // sequence epuisee
                value = _rep[menuReplyIndex];
            } else {
                // Ecran de saisie inconnu et aucune reponse configuree : on ne
                // tape RIEN. On memorise le texte pour que l'admin voie
                // exactement quoi configurer, plutot que d'envoyer au hasard.
                if (ecranNonTraite.isEmpty()) {
                    ecranNonTraite = signature;
                    Log.e(TAG, "ecran de saisie non reconnu, aucune reponse configuree");
                }
                return;
            }
            if (value == null || value.isEmpty()) return;

            lastActionAt = now;
            final String sig = signature;

            // Ecriture VERIFIEE puis validation. Le compteur d'ecrans n'avance
            // que si le clic a reellement eu lieu : un ecran non rempli n'est
            // jamais compte comme fait.
            ecrireEtValider(value, 0, () -> {
                stepsDone++;
                // L'index avance des que la valeur vient de la SEQUENCE
                // (y compris quand c'est l'etape {pin} d'Airtel), jamais
                // quand c'est le PIN separe d'Orange.
                if (!utilisePinArme) menuReplyIndex++;
                lastHandledSignature = sig;
                // Le code secret a bien ete saisi, qu'il vienne du champ
                // PIN separe ou de l'etape correspondante de la sequence.
                if (demandePin) pinSubmitted = true;
                Log.d(TAG, "ecran " + stepsDone + "/" + armedMaxSteps
                        + " valide pour retrait=" + armedRetraitId
                        + (demandePin ? " [PIN]" : " [menu]"));
            });

        } catch (Exception e) {
            Log.e(TAG, "onAccessibilityEvent: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() { /* rien */ }

    // ------------------------------------------------------------------
    // Detection de la boite de dialogue USSD
    // ------------------------------------------------------------------

    /** Paquets qui affichent les boites USSD/MMI selon les constructeurs. */
    private static final String[] PHONE_PACKAGES = {
            "com.android.phone",
            "com.android.server.telecom",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.android.incallui",
            "com.miui.securitycenter",
            "com.android.dialer",
            "com.transsion.phonemanager",
            "com.oppo.phone",
            "com.coloros.phonemanager",
            "com.huawei.systemmanager"
    };

    /**
     * Marqueurs de contenu propres aux menus USSD Mobile Money (mg / fr / en).
     * Garde-fou : on ne saisit JAMAIS le PIN dans une fenetre quelconque, meme
     * si le paquet emetteur est inconnu.
     */
    private static final String[] USSD_TEXT_MARKERS = {
            "kaody miafina", "ampidiro",
            "pejy voalohany", "hiverina",
            "sarany", "handefa vola",
            "code secret", "code pin", "votre pin",
            "mot de passe", "transfert",
            "enter your pin", "enter pin"
    };

    private static boolean containsUssdMarker(String texte) {
        if (TextUtils.isEmpty(texte)) return false;
        String t = texte.toLowerCase(Locale.ROOT);
        for (String m : USSD_TEXT_MARKERS) if (t.contains(m)) return true;
        return false;
    }

    private boolean looksLikeUssdDialog(AccessibilityNodeInfo root, AccessibilityEvent event) {
        // 1) Paquet emetteur connu.
        //    Comparaison "contient" et non "egal" : les ROM constructeurs ajoutent
        //    des suffixes (com.android.phone.xxx, com.transsion.phone...) et la
        //    comparaison stricte faisait echouer la detection -> aucune saisie.
        CharSequence pkgCs = root.getPackageName() != null ? root.getPackageName()
                : (event != null ? event.getPackageName() : null);
        String pkg = pkgCs == null ? "" : pkgCs.toString().toLowerCase(Locale.ROOT);
        for (String p : PHONE_PACKAGES) {
            if (pkg.equals(p) || pkg.startsWith(p)) return true;
        }
        if (pkg.contains("dialer") || pkg.contains("incallui")
                || pkg.contains("telecom") || pkg.contains("phone")) return true;

        // 2) Repli : dialogue systeme avec champ de saisie
        CharSequence cls = event != null ? event.getClassName() : null;
        if (cls != null && cls.toString().toLowerCase(Locale.ROOT).contains("alertdialog")) {
            return findEditable(root) != null;
        }

        // 3) Dernier repli, volontairement restrictif : fenetre inconnue MAIS
        //    dont le texte est manifestement un menu USSD Mobile Money.
        if (findEditable(root) != null && containsUssdMarker(collectText(root))) return true;

        return false;
    }

    /** Concatene les textes visibles (sert au compte rendu serveur). */
    private String collectText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        collectTextRec(node, sb, 0);
        return sb.toString().trim();
    }

    private void collectTextRec(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 25) return;
        try {
            CharSequence t = node.getText();
            // On ignore le contenu des champs de saisie : c'est le PIN
            boolean editable = isEditableNode(node);
            if (!editable && t != null && t.length() > 0) {
                String s = t.toString().trim();
                if (!s.isEmpty() && sb.indexOf(s) < 0) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(s);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                collectTextRec(node.getChild(i), sb, depth + 1);
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------
    // Champ de saisie
    // ------------------------------------------------------------------

    private static boolean isEditableNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && n.isEditable()) return true;
            CharSequence cls = n.getClassName();
            return cls != null && cls.toString().toLowerCase(Locale.ROOT).contains("edittext");
        } catch (Exception e) { return false; }
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        return findEditableRec(node, 0);
    }

    private AccessibilityNodeInfo findEditableRec(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 25) return null;
        try {
            if (isEditableNode(node) && node.isVisibleToUser()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo found = findEditableRec(node.getChild(i), depth + 1);
                if (found != null) return found;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Ecrit le texte dans le champ, avec repli presse-papier si ACTION_SET_TEXT echoue. */
    /** Nombre d'essais d'ecriture avant d'abandonner un ecran. */
    private static final int  SAISIE_ESSAIS_MAX = 5;
    private static final long SAISIE_1ER_DELAI_MS = 350L;
    private static final long SAISIE_RETRY_MS     = 250L;

    /**
     * Ecrit une valeur dans le champ, VERIFIE qu'elle y est vraiment, PUIS
     * seulement valide.
     *
     * Pourquoi : ACTION_SET_TEXT renvoie true meme quand la boite de dialogue
     * est encore en cours d'affichage — le texte n'est alors pas enregistre.
     * L'ancien code cliquait "Envoyer" apres un simple delai fixe : il arrivait
     * donc d'envoyer un champ VIDE. L'operateur fermait la session et la
     * sequence restait bloquee sans que rien ne soit ecrit, exactement le
     * symptome constate (consultation de solde comme retrait).
     *
     * Champ masque (PIN) : le systeme ne rend pas toujours le texte reel. On
     * verifie alors la LONGUEUR ; au dernier essai on valide quand meme, pour
     * ne pas casser le cas Orange qui fonctionnait deja.
     *
     * @param onValide execute UNIQUEMENT si le clic de validation a reussi.
     */
    private void ecrireEtValider(final String value, final int essai, final Runnable onValide) {
        ecrireEtValider(value, essai, false, onValide);
    }

    private void ecrireEtValider(final String value, final int essai,
                                 final boolean ecritureAcceptee, final Runnable onValide) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r == null) return;
                AccessibilityNodeInfo champ = findEditable(r);
                if (champ == null) return;              // boite disparue : un nouvel evenement suivra

                CharSequence cur = champ.getText();
                String actuel = (cur == null) ? "" : cur.toString().trim();
                boolean masque = false;
                try { masque = champ.isPassword(); } catch (Exception ignore) {}

                boolean enPlace = masque ? (actuel.length() == value.length())
                                         : value.equals(actuel);

                if (!enPlace) {
                    if (essai >= SAISIE_ESSAIS_MAX) {
                        // DERNIER RECOURS — ne jamais faire moins bien qu'avant.
                        // Certains champs (PIN, ROM constructeur) ne restituent
                        // jamais leur contenu a l'accessibilite : la verification
                        // echouerait alors indefiniment et le retrait resterait
                        // fige, alors que l'ancien code validait et fonctionnait.
                        // Si notre ecriture a ete ACCEPTEE au moins une fois, on
                        // valide comme avant. Sinon on s'abstient : rien n'a ete
                        // ecrit, envoyer serait envoyer du vide.
                        if (ecritureAcceptee) {
                            Log.d(TAG, "champ non verifiable (masque=" + masque
                                    + ") -> validation en dernier recours");
                            if (clickSendButton(r)) onValide.run();
                        } else {
                            Log.e(TAG, "champ de saisie toujours vide apres "
                                    + essai + " essais — aucune validation envoyee");
                        }
                        return;
                    }
                    // Le champ peut contenir un reliquat : ACTION_SET_TEXT ecrase.
                    boolean ok = setNodeText(champ, value);
                    ecrireEtValider(value, essai + 1, ecritureAcceptee || ok, onValide);
                    return;
                }

                // Le texte est REELLEMENT dans le champ : on peut valider.
                if (clickSendButton(r)) {
                    onValide.run();
                } else {
                    Log.e(TAG, "bouton d'envoi introuvable dans la boite USSD");
                }
            } catch (Exception e) {
                Log.e(TAG, "ecrireEtValider: " + e.getMessage());
            }
        }, essai == 0 ? SAISIE_1ER_DELAI_MS : SAISIE_RETRY_MS);
    }

    private boolean setNodeText(AccessibilityNodeInfo node, String value) {
        if (node == null || value == null) return false;
        try {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true;

            // Repli : focus + collage depuis le presse-papier (certaines ROM)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ussd", value));
                return node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            }
        } catch (Exception e) {
            Log.e(TAG, "setNodeText: " + e.getMessage());
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Bouton de validation
    // ------------------------------------------------------------------

    /** Libelles de validation rencontres (fr, mg, en, + ROM constructeurs). */
    private static final String[] SEND_LABELS = {
            "envoyer", "send", "ok", "alefa", "valider", "confirmer", "confirm",
            "continuer", "continue", "suivant", "next", "yes", "eny", "soumettre",
            "submit", "envoi", "accepter", "accept"
    };

    /**
     * Ecran INTERMEDIAIRE d'attente : l'operateur annonce que la demande est en
     * cours et affiche une boite SANS champ de saisie, avec un bouton OK seul.
     * Airtel Madagascar en affiche une apres un choix de menu :
     *   "Eo ampanatontosana ny fangatahana, tsindrio ny ok na mahandrasa kely."
     * Sans traitement, le service ne trouvait aucun champ editable, ne touchait
     * a rien, et la sequence restait bloquee sur cette boite jusqu'au delai.
     * On clique OK pour laisser la session continuer — SANS consommer d'etape
     * ni avancer dans la sequence : aucune valeur n'est saisie ici.
     */
    private static final String[] ATTENTE_MARKERS = {
            "ampanatontosana", "fangatahana", "andraso", "mahandrasa",
            "tsindrio ny ok", "en cours de traitement", "traitement en cours",
            "veuillez patienter", "patientez", "merci de patienter",
            "please wait", "processing", "request is being processed",
            "your request is being"
    };

    /** true si l'on peut (re)cliquer OK sur cet ecran d'attente maintenant. */
    private static boolean peutCliquerAttente(String texte) {
        long now = System.currentTimeMillis();
        String sig = (texte == null || texte.isEmpty()) ? "<vide>" : texte;
        if (sig.equals(lastAttenteSignature) && (now - lastAttenteAt) < ATTENTE_REPEAT_MS) {
            return false;                       // clic tout juste emis sur cette boite
        }
        lastAttenteSignature = sig;
        lastAttenteAt = now;
        attenteClics++;
        return true;
    }

    /**
     * Un ecran de RESULTAT contient une valeur : montant, solde, identifiant de
     * transaction. Meme s'il emploie un mot d'attente ("fangatahana voaray..."),
     * ce n'est PAS un ecran intermediaire : le confondre ferait cliquer OK sans
     * jamais lire le solde, sur Telma comme sur Orange.
     */
    private static final String[] RESULTAT_MARKERS = {
            "toe bola", "toe-bola", "solde", "trans id", "transaction id",
            "reference", "ref:", "ariary", " ar ", " fc ", "mga", "montant",
            "vola voaray", "voaray tsara", "recu", "recus"
    };

    private static boolean ecranResultat(String texte) {
        if (TextUtils.isEmpty(texte)) return false;
        String t = texte.toLowerCase();
        for (String m : RESULTAT_MARKERS) {
            if (t.contains(m)) return true;
        }
        return false;
    }

    /** Plafond de clics OK d'attente par operation : evite toute boucle sans fin. */
    private static final int ATTENTE_CLICS_MAX = 6;
    private static volatile int attenteClics = 0;

    private static boolean ecranDattente(String texte) {
        if (TextUtils.isEmpty(texte)) return false;
        // Un ecran portant une valeur est un resultat, jamais une attente.
        if (ecranResultat(texte)) return false;
        if (attenteClics >= ATTENTE_CLICS_MAX) return false;
        String t = texte.toLowerCase();
        for (String m : ATTENTE_MARKERS) {
            if (t.contains(m)) { return true; }
        }
        return false;
    }

    /** Libelles a NE JAMAIS cliquer. */
    private static final String[] CANCEL_LABELS = {
            "annuler", "cancel", "aoka", "fermer", "close", "non", "no",
            "tsia", "retour", "back", "dismiss", "quitter"
    };

    private boolean clickSendButton(AccessibilityNodeInfo root) {
        // 1) Identifiants de vue standards Android (les plus fiables)
        String[] ids = {
                "android:id/button1",          // bouton positif d'AlertDialog
                "com.android.phone:id/button1"
        };
        for (String id : ids) {
            AccessibilityNodeInfo n = findByViewId(root, id);
            if (n != null && clickNode(n)) return true;
        }

        // 2) Recherche par libelle (multilingue), en excluant les libelles d'annulation
        List<AccessibilityNodeInfo> buttons = new ArrayList<>();
        collectClickable(root, buttons, 0);
        for (AccessibilityNodeInfo b : buttons) {
            String label = labelOf(b);
            if (label.isEmpty() || isCancelLabel(label)) continue;
            for (String s : SEND_LABELS) {
                if (label.equals(s) || label.startsWith(s)) {
                    if (clickNode(b)) return true;
                }
            }
        }

        // 3) Dernier repli : s'il n'y a qu'UN SEUL bouton cliquable non-annulation
        AccessibilityNodeInfo unique = null;
        int count = 0;
        for (AccessibilityNodeInfo b : buttons) {
            String label = labelOf(b);
            if (isCancelLabel(label)) continue;
            if (!isButtonLike(b)) continue;
            count++;
            unique = b;
        }
        if (count == 1 && unique != null) return clickNode(unique);

        return false;
    }

    /**
     * Clique volontairement sur ANNULER / CANCEL. Utilise uniquement pour fermer
     * un ecran dont la transaction est deja terminee — jamais pendant une
     * transaction en cours.
     */
    /** Libelles fermant une boite d'information/erreur. */
    private static final String[] DISMISS_LABELS = {
            "ok", "fermer", "close", "annuler", "cancel", "quitter", "hiala", "eny"
    };

    /**
     * Ferme une boite d'erreur (bouton OK, ou a defaut le bouton negatif).
     * Utilise UNIQUEMENT sur un ecran d'echec definitif : aucune transaction
     * n'est en cours a ce moment, il n'y a donc rien a valider par megarde.
     */
    private boolean clickDismissButton(AccessibilityNodeInfo root) {
        // 1) Boutons standard d'AlertDialog : positif (OK) puis neutre puis negatif
        String[] ids = {
            "android:id/button1", "com.android.phone:id/button1",
            "android:id/button3", "android:id/button2"
        };
        for (String id : ids) {
            AccessibilityNodeInfo n = findByViewId(root, id);
            if (n != null && clickNode(n)) return true;
        }
        // 2) Par libelle
        List<AccessibilityNodeInfo> buttons = new ArrayList<>();
        collectClickable(root, buttons, 0);
        for (AccessibilityNodeInfo b : buttons) {
            String label = labelOf(b).toLowerCase(Locale.ROOT).trim();
            if (label.isEmpty()) continue;
            for (String l : DISMISS_LABELS) {
                if (label.equals(l) && clickNode(b)) return true;
            }
        }
        return false;
    }

    private boolean clickCancelButton(AccessibilityNodeInfo root) {
        // 1) Bouton negatif standard d'AlertDialog
        String[] ids = { "android:id/button2", "com.android.phone:id/button2" };
        for (String id : ids) {
            AccessibilityNodeInfo n = findByViewId(root, id);
            if (n != null && clickNode(n)) return true;
        }
        // 2) Par libelle
        List<AccessibilityNodeInfo> buttons = new ArrayList<>();
        collectClickable(root, buttons, 0);
        for (AccessibilityNodeInfo b : buttons) {
            String label = labelOf(b);
            if (!label.isEmpty() && isCancelLabel(label) && clickNode(b)) return true;
        }
        return false;
    }

    private static boolean isCancelLabel(String label) {
        for (String c : CANCEL_LABELS) {
            if (label.equals(c) || label.startsWith(c)) return true;
        }
        return false;
    }

    private static boolean isButtonLike(AccessibilityNodeInfo n) {
        try {
            CharSequence cls = n.getClassName();
            if (cls == null) return false;
            String c = cls.toString().toLowerCase(Locale.ROOT);
            return c.contains("button") || c.contains("textview");
        } catch (Exception e) { return false; }
    }

    private static String labelOf(AccessibilityNodeInfo n) {
        try {
            CharSequence t = n.getText();
            if (t == null || t.length() == 0) t = n.getContentDescription();
            return t == null ? "" : t.toString().trim().toLowerCase(Locale.ROOT);
        } catch (Exception e) { return ""; }
    }

    /** Clique le noeud, ou son premier ancetre cliquable. */
    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        int guard = 0;
        while (n != null && guard++ < 8) {
            try {
                if (n.isClickable() && n.isEnabled()) {
                    return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                n = n.getParent();
            } catch (Exception e) { return false; }
        }
        return false;
    }

    private AccessibilityNodeInfo findByViewId(AccessibilityNodeInfo root, String id) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return null;
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(id);
            if (list != null && !list.isEmpty()) return list.get(0);
        } catch (Exception ignored) { }
        return null;
    }

    private void collectClickable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 25 || out.size() > 80) return;
        try {
            if (node.isVisibleToUser() && (node.isClickable() || isButtonLike(node))) out.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                collectClickable(node.getChild(i), out, depth + 1);
            }
        } catch (Exception ignored) { }
    }
}
