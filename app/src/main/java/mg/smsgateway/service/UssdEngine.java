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
    private static int getSubIdForOperator(Context context, String ussdCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return -1;
        try {
            SubscriptionManager sm = (SubscriptionManager)
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return -1;
            List<SubscriptionInfo> sims = sm.getActiveSubscriptionInfoList();
            if (sims == null) return -1;
            for (SubscriptionInfo info : sims) {
                String op = mg.smsgateway.utils.SimUtils.getOperatorFromSubId(info.getSubscriptionId()).toUpperCase();
                if (ussdCode.startsWith("#144") && op.contains("ORANGE")) return info.getSubscriptionId();
                if (ussdCode.startsWith("*155") && (op.contains("MVOLA") || op.contains("YAS") || op.contains("TELMA"))) return info.getSubscriptionId();
                if (ussdCode.startsWith("*123") && op.contains("AIRTEL")) return info.getSubscriptionId();
            }
        } catch (Exception e) { Log.e("UssdEngine", "getSubIdForOperator: " + e.getMessage()); }
        return -1;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void sendUssd(Context context, String retraitId,
                                 String ussdCode, UssdCallback callback) {
        try {
            TelephonyManager baseTm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
            if (baseTm == null) {
                callback.onResult(retraitId, false, "TelephonyManager null");
                return;
            }
            int subId = getSubIdForOperator(context, ussdCode);
            TelephonyManager tm = subId >= 0 ? baseTm.createForSubscriptionId(subId) : baseTm;
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
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void checkBalance(Context context, String operator,
                                    String ussdCode, UssdCallback callback) {
        try {
            TelephonyManager tm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) {
                callback.onResult(operator, false, "TelephonyManager null");
                return;
            }
            tm.sendUssdRequest(ussdCode, new TelephonyManager.UssdResponseCallback() {
                @Override
                public void onReceiveUssdResponse(TelephonyManager tm,
                                                  String request, CharSequence response) {
                    String resp = response != null ? response.toString() : "";
                    Log.d(TAG, "Balance response [" + operator + "]: " + resp);
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