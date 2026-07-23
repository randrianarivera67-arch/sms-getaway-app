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

    // ---- Etat partage (arme par GatewayService avant l'envoi du code USSD) ----
    private static volatile String  armedPin      = null;
    private static volatile long    armedAt       = 0L;
    private static volatile String  armedRetraitId = null;
    private static volatile String  lastDialogText = "";
    private static volatile boolean pinSubmitted   = false;

    private long lastActionAt = 0L;

    /**
     * Arme le service : le prochain dialogue USSD demandant une saisie recevra ce PIN.
     * Appele juste AVANT l'envoi du code USSD.
     */
    public static void arm(String pin, String retraitId) {
        armedPin       = (pin == null) ? null : pin.trim();
        armedRetraitId = retraitId;
        armedAt        = System.currentTimeMillis();
        lastDialogText = "";
        pinSubmitted   = false;
        Log.d(TAG, "arme pour retrait=" + retraitId + " (pin masque, " +
                (armedPin == null ? 0 : armedPin.length()) + " chiffres)");
    }

    /** Desarme immediatement (fin de transaction ou annulation). */
    public static void disarm() {
        armedPin = null;
        armedRetraitId = null;
        armedAt = 0L;
    }

    /** true si le PIN a effectivement ete saisi et valide depuis le dernier arm(). */
    public static boolean wasPinSubmitted() { return pinSubmitted; }

    /** Dernier texte lu dans une boite de dialogue USSD (pour le compte rendu serveur). */
    public static String getLastDialogText() { return lastDialogText; }

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
        return armedPin != null
                && !armedPin.isEmpty()
                && (System.currentTimeMillis() - armedAt) < ARM_TIMEOUT_MS;
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
            if (!TextUtils.isEmpty(text)) lastDialogText = text;

            if (!isArmed()) return;

            long now = System.currentTimeMillis();
            if (now - lastActionAt < MIN_ACTION_INTERVAL_MS) return;

            AccessibilityNodeInfo input = findEditable(root);
            if (input == null) return;              // dialogue sans saisie : rien a faire

            // Deja rempli (evenement redondant) : on ne retape pas
            CharSequence current = input.getText();
            boolean alreadyFilled = current != null && current.length() > 0;

            if (!alreadyFilled) {
                if (!setNodeText(input, armedPin)) {
                    Log.e(TAG, "impossible d'ecrire dans le champ de saisie");
                    return;
                }
            }

            lastActionAt = now;

            // Laisse le systeme enregistrer le texte avant de valider
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                AccessibilityNodeInfo r2 = null;
                try {
                    r2 = getRootInActiveWindow();
                    if (r2 == null) return;
                    if (clickSendButton(r2)) {
                        pinSubmitted = true;
                        armedPin = null;            // usage unique
                        Log.d(TAG, "PIN saisi et valide pour retrait=" + armedRetraitId);
                    } else {
                        Log.e(TAG, "bouton d'envoi introuvable dans la boite USSD");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "clic envoi: " + e.getMessage());
                }
            }, 350L);

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

    private boolean looksLikeUssdDialog(AccessibilityNodeInfo root, AccessibilityEvent event) {
        // 1) Paquet emetteur connu
        CharSequence pkgCs = root.getPackageName() != null ? root.getPackageName()
                : (event != null ? event.getPackageName() : null);
        String pkg = pkgCs == null ? "" : pkgCs.toString().toLowerCase(Locale.ROOT);
        for (String p : PHONE_PACKAGES) {
            if (pkg.equals(p)) return true;
        }
        // 2) Repli : un dialogue systeme contenant un champ de saisie + un bouton
        //    (couvre les ROM constructeurs non listees ci-dessus)
        CharSequence cls = event != null ? event.getClassName() : null;
        if (cls != null && cls.toString().toLowerCase(Locale.ROOT).contains("alertdialog")) {
            return findEditable(root) != null;
        }
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
