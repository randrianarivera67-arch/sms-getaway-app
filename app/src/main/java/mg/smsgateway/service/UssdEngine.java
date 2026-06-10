package mg.smsgateway.service;

import android.content.Context;
import android.os.Build;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import androidx.annotation.RequiresApi;
import mg.smsgateway.network.ApiClient;
import mg.smsgateway.utils.Prefs;

public class UssdEngine {

    private static final String TAG = "UssdEngine";

    public interface UssdCallback {
        void onResult(String retraitId, boolean success, String response);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void sendUssd(Context context, String retraitId,
                                 String ussdCode, UssdCallback callback) {
        try {
            // Mampiasa TelecomManager mba hanala ny dialog USSD auto
            android.telecom.TelecomManager telecom =
                (android.telecom.TelecomManager)
                    context.getSystemService(Context.TELECOM_SERVICE);

            TelephonyManager tm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);

            if (tm == null) {
                callback.onResult(retraitId, false, "TelephonyManager null");
                return;
            }

            // Manala ny USSD dialog automatique raha misy
            if (telecom != null) {
                try {
                    android.net.Uri ussdUri = android.net.Uri.fromParts("tel", ussdCode, null);
                    android.os.Bundle extras = new android.os.Bundle();
                    telecom.placeCall(ussdUri, extras);
                } catch (Exception ignored) {
                    // Fallback: mampiasa TelephonyManager mivantana
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
                    return;
                }
            }

            // TelephonyManager sendUssdRequest — callback marina
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
}
