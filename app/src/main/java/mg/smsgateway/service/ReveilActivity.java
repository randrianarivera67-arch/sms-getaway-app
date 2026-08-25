package mg.smsgateway.service;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

/**
 * Ecran invisible dont le seul role est de PASSER L'ECRAN DE VERROUILLAGE
 * avant qu'une operation USSD ne compose.
 *
 * POURQUOI
 * --------
 * Quand l'appareil dort, le service reveille bien le telephone : l'ecran
 * s'allume. Mais l'ecran de verrouillage reste au premier plan, et la boite
 * USSD ouverte par le telephone apparait DERRIERE lui. Elle n'est alors ni
 * visible ni accessible : plus aucune saisie, plus aucun bouton clique, la
 * session USSD reste ouverte jusqu'a expiration. C'est ce qui faisait echouer
 * tout retrait ou toute consultation de solde ecran eteint, alors que la meme
 * operation reussissait des qu'on deverrouillait a la main.
 *
 * Un verrou d'ecran (WakeLock) ne suffit pas : il allume l'ecran sans lever le
 * verrouillage — il aggravait meme la situation en laissant l'appareil dans un
 * etat "allume mais verrouille".
 *
 * COMMENT
 * -------
 * C'est la voie officielle depuis Android 8.1 : une activite qui se declare
 * "visible sur l'ecran de verrouillage" et demande au systeme de le lever.
 * Elle se ferme aussitot, sans rien afficher.
 *
 * LIMITE ASSUMEE
 * --------------
 * requestDismissKeyguard ne leve QUE les verrouillages sans secret (glissement).
 * Si un code PIN ou un schema protege l'appareil, Android exige que l'humain le
 * saisisse — aucune application ne peut contourner cela, et c'est voulu. Le
 * telephone passerelle doit donc rester sans code de verrouillage.
 */
public class ReveilActivity extends Activity {

    private static final String TAG = "ReveilActivity";

    /** Delai laisse au systeme pour retirer l'ecran de verrouillage. */
    private static final long FERMETURE_MS = 700L;

    /**
     * Leve le verrouillage si l'appareil est verrouille. Ne fait rien sinon —
     * inutile d'ouvrir une activite quand l'ecran est deja utilisable.
     *
     * @return true si un reveil a ete demande (l'appelant doit laisser au
     *         systeme le temps d'y donner suite avant de composer).
     */
    public static boolean reveillerSiVerrouille(Context context) {
        try {
            if (context == null) return false;
            KeyguardManager km = (KeyguardManager)
                    context.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null || !km.isKeyguardLocked()) return false;

            Intent i = new Intent(context, ReveilActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                     | Intent.FLAG_ACTIVITY_NO_ANIMATION
                     | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            context.startActivity(i);
            Log.d(TAG, "appareil verrouille : demande de reveil");
            return true;
        } catch (Throwable t) {
            // Un echec ici ne doit jamais empecher la composition : au pire on
            // se retrouve dans la situation d'avant, pas dans une situation pire.
            Log.e(TAG, "reveillerSiVerrouille: " + t.getMessage());
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
                KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                if (km != null) km.requestDismissKeyguard(this, null);
            } else {
                // Anciennes versions : les memes effets passent par les
                // drapeaux de fenetre, depuis remplaces par les appels ci-dessus.
                getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                      | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                      | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                      | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Throwable t) {
            Log.e(TAG, "onCreate: " + t.getMessage());
        }

        // On se retire tout de suite : cette activite ne doit jamais rester
        // devant l'utilisateur ni devant la boite USSD qui va suivre.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try { finish(); } catch (Throwable ignore) {}
                try { overridePendingTransition(0, 0); } catch (Throwable ignore) {}
            }
        }, FERMETURE_MS);
    }
}
