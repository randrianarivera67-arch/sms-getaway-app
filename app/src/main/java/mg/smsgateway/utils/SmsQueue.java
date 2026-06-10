package mg.smsgateway.utils;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import mg.smsgateway.model.SmsMessage;
import java.util.ArrayList;
import java.util.List;

public class SmsQueue extends SQLiteOpenHelper {

    private static final String TAG        = "SmsQueue";
    private static final String DB_NAME    = "sms_queue.db";
    private static final int    DB_VERSION = 3;
    private static final String TABLE      = "sms_queue";

    private static SmsQueue instance;

    public static synchronized SmsQueue getInstance(Context context) {
        if (instance == null) {
            instance = new SmsQueue(context.getApplicationContext());
        }
        return instance;
    }

    private SmsQueue(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "id TEXT PRIMARY KEY," +
                "from_number TEXT," +
                "message TEXT," +
                "sim TEXT," +
                "sim_slot INTEGER DEFAULT -1," +
                "timestamp TEXT," +
                "status TEXT DEFAULT 'pending'," +
                "retry_count INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Migration propre : ajout colonne sim_slot si absente (v2 -> v3)
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN sim_slot INTEGER DEFAULT -1");
            } catch (Exception e) {
                Log.w(TAG, "sim_slot column may already exist: " + e.getMessage());
            }
        }
    }

    public void addToQueue(SmsMessage sms) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("id", sms.getId());
            values.put("from_number", sms.getFrom());
            values.put("message", sms.getMessage());
            values.put("sim", sms.getSim());
            values.put("sim_slot", sms.getSimSlot());
            values.put("timestamp", sms.getTimestamp());
            values.put("status", "pending");
            values.put("retry_count", 0);
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Exception e) {
            Log.e(TAG, "addToQueue error: " + e.getMessage());
        }
    }

    public List<SmsMessage> getPendingMessages() {
        List<SmsMessage> list = new ArrayList<>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            int maxRetry = 5;
            Cursor cursor = db.query(TABLE, null,
                    "status = ? AND retry_count < ?",
                    new String[]{"pending", String.valueOf(maxRetry)},
                    null, null, "rowid ASC", "20");
            while (cursor.moveToNext()) {
                SmsMessage sms = new SmsMessage(
                        cursor.getString(cursor.getColumnIndexOrThrow("from_number")),
                        cursor.getString(cursor.getColumnIndexOrThrow("message")),
                        cursor.getString(cursor.getColumnIndexOrThrow("sim")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("sim_slot")));
                sms.setId(cursor.getString(cursor.getColumnIndexOrThrow("id")));
                sms.setRetryCount(cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")));
                list.add(sms);
            }
            cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "getPendingMessages error: " + e.getMessage());
        }
        return list;
    }

    public List<SmsMessage> getRecentMessages(int limit) {
        List<SmsMessage> list = new ArrayList<>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE, null, null, null,
                    null, null, "rowid DESC", String.valueOf(limit));
            while (cursor.moveToNext()) {
                SmsMessage sms = new SmsMessage(
                        cursor.getString(cursor.getColumnIndexOrThrow("from_number")),
                        cursor.getString(cursor.getColumnIndexOrThrow("message")),
                        cursor.getString(cursor.getColumnIndexOrThrow("sim")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("sim_slot")));
                sms.setId(cursor.getString(cursor.getColumnIndexOrThrow("id")));
                sms.setStatus(cursor.getString(cursor.getColumnIndexOrThrow("status")));
                list.add(sms);
            }
            cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "getRecentMessages error: " + e.getMessage());
        }
        return list;
    }

    public void markAsSent(String id) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("status", "sent");
            db.update(TABLE, values, "id = ?", new String[]{id});
        } catch (Exception e) {
            Log.e(TAG, "markAsSent error: " + e.getMessage());
        }
    }

    public void markAsFailed(String id) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL(
                "UPDATE " + TABLE + " SET " +
                "retry_count = retry_count + 1, " +
                "status = CASE WHEN retry_count + 1 >= 5 THEN 'failed' ELSE 'pending' END " +
                "WHERE id = ?",
                new String[]{id}
            );
        } catch (Exception e) {
            Log.e(TAG, "markAsFailed error: " + e.getMessage());
        }
    }

    public int getPendingCount() {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE status = 'pending' AND retry_count < 5", null);
            if (c.moveToFirst()) { int n = c.getInt(0); c.close(); return n; }
            c.close();
        } catch (Exception e) {
            Log.e(TAG, "getPendingCount error: " + e.getMessage());
        }
        return 0;
    }

    public int getFailedCount() {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE status = 'failed'", null);
            if (c.moveToFirst()) { int n = c.getInt(0); c.close(); return n; }
            c.close();
        } catch (Exception e) {
            Log.e(TAG, "getFailedCount error: " + e.getMessage());
        }
        return 0;
    }

    public int getTotalCount() {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (c.moveToFirst()) { int n = c.getInt(0); c.close(); return n; }
            c.close();
        } catch (Exception e) {
            Log.e(TAG, "getTotalCount error: " + e.getMessage());
        }
        return 0;
    }

    public void deleteSms(String id) {
        try { getWritableDatabase().delete("sms_queue","id = ?",new String[]{id}); }
        catch(Exception e){ android.util.Log.e("SmsQueue","deleteSms: "+e.getMessage()); }
    }

    public void clearAll() {
        try { getWritableDatabase().delete(TABLE, null, null); }
        catch(Exception e){ android.util.Log.e("SmsQueue","clearAll: "+e.getMessage()); }
    }
    public void saveReceived(mg.smsgateway.model.SmsMessage sms,String status) {
        try {
            android.content.ContentValues v=new android.content.ContentValues();
            v.put("id",sms.getId()); v.put("from_number",sms.getFrom());
            v.put("message",sms.getMessage()); v.put("sim",sms.getSim());
            v.put("sim_slot",sms.getSimSlot()); v.put("timestamp",sms.getTimestamp());
            v.put("status",status); v.put("retry_count",0);
            getWritableDatabase().insertWithOnConflict("sms_queue",null,v,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        } catch(Exception e){ android.util.Log.e("SmsQueue","saveReceived: "+e.getMessage()); }
    }

}