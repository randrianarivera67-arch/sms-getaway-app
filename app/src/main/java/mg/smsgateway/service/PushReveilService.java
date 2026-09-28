package mg.smsgateway.service;

import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import mg.smsgateway.utils.Prefs;

/**
 * Reveil par notification distante.
 *
 * Le service d'accessibilite, mis en veille par le systeme apres une longue
 * inactivite, ne compose plus les ecrans USSD : un retrait partait, le code
 * etait bien compose, mais l'ecran suivant n'etait jamais renseigne. Rien dans
 * l'application ne pouvait le reveiller — ni l'ouvrir, ni un WakeLock.
 *
 * Un message FCM « high priority » sort le processus de la veille profonde ;
 * le systeme rattache alors de nouveau le service d'accessibilite. On relance
 * dans la foulee la passerelle et sa file, pour que le retrait en attente
 * reparte aussitot.
 *
 * Le message ne transporte aucune donnee sensible : il ne sert qu'a reveiller.
 */
public class PushReveilService extends FirebaseMessagingService {

    private static final String TAG = "PushReveil";

    @Override
    public void onNewToken(@NonNull String token) {
        // Le jeton identifie CET appareil aupres de Firebase. Il change a la
        // reinstallation ou au nettoyage des donnees : on le garde pour que le
        // prochain battement l'envoie au serveur.
        try {
            new Prefs(getApplicationContext()).setFcmToken(token);
            Log.d(TAG, "nouveau jeton FCM enregistre");
        } catch (Throwable t) {
            Log.e(TAG, "onNewToken: " + t.getMessage());
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Log.d(TAG, "reveil FCM recu");
        try {
            // Redemarrer le service de passerelle : son battement va aussitot
            // redemander au serveur les retraits en attente et les composer,
            // le service d'accessibilite etant de nouveau rattache.
            Intent i = new Intent(getApplicationContext(), GatewayService.class);
            i.setAction("mg.smsgateway.REVEIL_FCM");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(i);
            } else {
                getApplicationContext().startService(i);
            }
        } catch (Throwable t) {
            Log.e(TAG, "onMessageReceived: " + t.getMessage());
        }
    }
}
