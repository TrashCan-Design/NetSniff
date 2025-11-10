package com.netsniff.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Database helper for storing network traffic and managing blacklisted sites
 * Usage:
 * 1. Initialize: Allowed.initialize(context)
 * 2. Store traffic: Allowed.storeTraffic(...)
 * 3. Blacklist site: Allowed.addToBlacklist(domain)
 * 4. Check if blocked: Allowed.isBlacklisted(domain)
 */
public class Allowed extends SQLiteOpenHelper {
    private static final String TAG = "AllowedDB";

    // Database Info
    private static final String DATABASE_NAME = "netsniff.db";
    private static final int DATABASE_VERSION = 1;

    // Table Names
    private static final String TABLE_TRAFFIC = "traffic";
    private static final String TABLE_BLACKLIST = "blacklist";

    // Traffic Table Columns
    private static final String KEY_ID = "id";
    private static final String KEY_TIMESTAMP = "timestamp";
    private static final String KEY_SOURCE_IP = "source_ip";
    private static final String KEY_SOURCE_PORT = "source_port";
    private static final String KEY_DEST_IP = "dest_ip";
    private static final String KEY_DEST_PORT = "dest_port";
    private static final String KEY_PROTOCOL = "protocol";
    private static final String KEY_DIRECTION = "direction";
    private static final String KEY_SIZE = "size";
    private static final String KEY_APP_NAME = "app_name";
    private static final String KEY_PACKAGE_NAME = "package_name";
    private static final String KEY_UID = "uid";
    private static final String KEY_PAYLOAD = "payload";
    private static final String KEY_DOMAIN = "domain";

    // Blacklist Table Columns
    private static final String KEY_BL_ID = "id";
    private static final String KEY_BL_DOMAIN = "domain";
    private static final String KEY_BL_ADDED_TIME = "added_time";
    private static final String KEY_BL_ENABLED = "enabled";
    private static final String KEY_BL_DESCRIPTION = "description";

    // Singleton instance
    private static Allowed instance;

    // In-memory cache for blacklist (for performance)
    private static Set<String> blacklistCache = new HashSet<>();
    private static long lastCacheUpdate = 0;
    private static final long CACHE_REFRESH_INTERVAL = 30000; // 30 seconds

    // Legacy fields
    public String raddr = null;
    public int rport = 0;

    private Allowed(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    /**
     * Initialize the database (call this once in Application or Service onCreate)
     */
    public static synchronized void initialize(Context context) {
        if (instance == null) {
            instance = new Allowed(context.getApplicationContext());
            instance.refreshBlacklistCache();
            Log.d(TAG, "Database initialized");
        }
    }

    /**
     * Get singleton instance
     */
    public static synchronized Allowed getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Allowed not initialized. Call initialize(context) first.");
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Create Traffic Table
        String CREATE_TRAFFIC_TABLE = "CREATE TABLE " + TABLE_TRAFFIC + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_TIMESTAMP + " INTEGER,"
                + KEY_SOURCE_IP + " TEXT,"
                + KEY_SOURCE_PORT + " INTEGER,"
                + KEY_DEST_IP + " TEXT,"
                + KEY_DEST_PORT + " INTEGER,"
                + KEY_PROTOCOL + " TEXT,"
                + KEY_DIRECTION + " TEXT,"
                + KEY_SIZE + " INTEGER,"
                + KEY_APP_NAME + " TEXT,"
                + KEY_PACKAGE_NAME + " TEXT,"
                + KEY_UID + " INTEGER,"
                + KEY_PAYLOAD + " TEXT,"
                + KEY_DOMAIN + " TEXT"
                + ")";

        // Create Blacklist Table
        String CREATE_BLACKLIST_TABLE = "CREATE TABLE " + TABLE_BLACKLIST + "("
                + KEY_BL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_BL_DOMAIN + " TEXT UNIQUE,"
                + KEY_BL_ADDED_TIME + " INTEGER,"
                + KEY_BL_ENABLED + " INTEGER DEFAULT 1,"
                + KEY_BL_DESCRIPTION + " TEXT"
                + ")";

        db.execSQL(CREATE_TRAFFIC_TABLE);
        db.execSQL(CREATE_BLACKLIST_TABLE);

        // Indexes for faster lookups
        db.execSQL("CREATE INDEX idx_traffic_timestamp ON " + TABLE_TRAFFIC + "(" + KEY_TIMESTAMP + ")");
        db.execSQL("CREATE INDEX idx_traffic_domain ON " + TABLE_TRAFFIC + "(" + KEY_DOMAIN + ")");
        db.execSQL("CREATE INDEX idx_blacklist_domain ON " + TABLE_BLACKLIST + "(" + KEY_BL_DOMAIN + ")");

        Log.d(TAG, "Database tables created");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_TRAFFIC);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_BLACKLIST);
        onCreate(db);
    }

    // ==================== TRAFFIC STORAGE METHODS ====================

    /**
     * Store network traffic data
     */
    public static long storeTraffic(String sourceIp, int sourcePort, String destIp, int destPort,
                                    String protocol, String direction, int size, String appName,
                                    String packageName, int uid, String payload, String domain) {
        try {
            SQLiteDatabase db = getInstance().getWritableDatabase();
            ContentValues values = new ContentValues();

            values.put(KEY_TIMESTAMP, System.currentTimeMillis());
            values.put(KEY_SOURCE_IP, sourceIp);
            values.put(KEY_SOURCE_PORT, sourcePort);
            values.put(KEY_DEST_IP, destIp);
            values.put(KEY_DEST_PORT, destPort);
            values.put(KEY_PROTOCOL, protocol);
            values.put(KEY_DIRECTION, direction);
            values.put(KEY_SIZE, size);
            values.put(KEY_APP_NAME, appName);
            values.put(KEY_PACKAGE_NAME, packageName);
            values.put(KEY_UID, uid);
            values.put(KEY_PAYLOAD, payload);
            values.put(KEY_DOMAIN, domain);

            long id = db.insert(TABLE_TRAFFIC, null, values);
            if (id == -1) Log.e(TAG, "Failed to insert traffic record");
            return id;
        } catch (Exception e) {
            Log.e(TAG, "Error storing traffic", e);
            return -1;
        }
    }

    /**
     * Get all recent traffic
     */
    public static List<TrafficRecord> getAllTraffic(int limit) {
        List<TrafficRecord> records = new ArrayList<>();
        try {
            SQLiteDatabase db = getInstance().getReadableDatabase();
            String query = "SELECT * FROM " + TABLE_TRAFFIC +
                    " ORDER BY " + KEY_TIMESTAMP + " DESC LIMIT " + limit;

            Cursor cursor = db.rawQuery(query, null);
            if (cursor.moveToFirst()) {
                do {
                    records.add(new TrafficRecord(cursor));
                } while (cursor.moveToNext());
            }
            cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "Error reading traffic", e);
        }
        return records;
    }

    // ✅ FINAL FIXED METHOD
    /**
     * Get saved (old) traffic records from database (most recent first)
     */
    public static List<TrafficRecord> getOldTraffic() {
        List<TrafficRecord> records = new ArrayList<>();

        try {
            SQLiteDatabase db = getInstance().getReadableDatabase();
            Log.d(TAG, "🧩 Querying traffic count check...");
            Cursor testCursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_TRAFFIC, null);
            if (testCursor.moveToFirst()) {
                int count = testCursor.getInt(0);
                Log.d(TAG, "📊 Total traffic rows in DB: " + count);
            }
            testCursor.close();
            // ✅ Fetch 500 most recent saved packets
            String query = "SELECT * FROM " + TABLE_TRAFFIC +
                    " ORDER BY " + KEY_TIMESTAMP + " DESC LIMIT 500";

            Cursor cursor = db.rawQuery(query, null);

            if (cursor.moveToFirst()) {
                do {
                    records.add(new TrafficRecord(cursor));
                } while (cursor.moveToNext());
            }

            cursor.close();
            Log.d(TAG, "✅ getOldTraffic() loaded " + records.size() + " packets from DB");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error reading old traffic", e);
        }

        return records;
    }

    /**
     * Clear old traffic records (keep last N days)
     */
    public static int clearOldTraffic(int daysToKeep) {
        try {
            SQLiteDatabase db = getInstance().getWritableDatabase();
            long cutoffTime = System.currentTimeMillis() - (daysToKeep * 24L * 60 * 60 * 1000);

            int deleted = db.delete(TABLE_TRAFFIC,
                    KEY_TIMESTAMP + " < ?",
                    new String[]{String.valueOf(cutoffTime)});

            Log.d(TAG, "Cleared " + deleted + " old traffic records");
            return deleted;
        } catch (Exception e) {
            Log.e(TAG, "Error clearing old traffic", e);
            return 0;
        }
    }

    // ==================== BLACKLIST METHODS ====================

    public static boolean addToBlacklist(String domain, String description) {
        try {
            SQLiteDatabase db = getInstance().getWritableDatabase();
            ContentValues values = new ContentValues();

            values.put(KEY_BL_DOMAIN, domain.toLowerCase());
            values.put(KEY_BL_ADDED_TIME, System.currentTimeMillis());
            values.put(KEY_BL_ENABLED, 1);
            values.put(KEY_BL_DESCRIPTION, description);

            long id = db.insertWithOnConflict(TABLE_BLACKLIST, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);

            if (id != -1) {
                blacklistCache.add(domain.toLowerCase());
                Log.d(TAG, "Added to blacklist: " + domain);
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error adding to blacklist", e);
            return false;
        }
    }



    public static boolean removeFromBlacklist(String domain) {
        try {
            SQLiteDatabase db = getInstance().getWritableDatabase();
            int deleted = db.delete(TABLE_BLACKLIST,
                    KEY_BL_DOMAIN + " = ?",
                    new String[]{domain.toLowerCase()});
            if (deleted > 0) {
                blacklistCache.remove(domain.toLowerCase());
                Log.d(TAG, "Removed from blacklist: " + domain);
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error removing from blacklist", e);
            return false;
        }
    }

    public static boolean isBlacklisted(String domain) {
        if (System.currentTimeMillis() - lastCacheUpdate > CACHE_REFRESH_INTERVAL) {
            getInstance().refreshBlacklistCache();
        }

        if (domain == null || domain.isEmpty()) return false;
        String lowerDomain = domain.toLowerCase();

        if (blacklistCache.contains(lowerDomain)) return true;

        for (String blocked : blacklistCache) {
            if (lowerDomain.endsWith("." + blocked) || lowerDomain.equals(blocked)) {
                return true;
            }
        }
        return false;
    }

    public static List<BlacklistEntry> getAllBlacklist() {
        List<BlacklistEntry> entries = new ArrayList<>();
        try {
            SQLiteDatabase db = getInstance().getReadableDatabase();
            String query = "SELECT * FROM " + TABLE_BLACKLIST +
                    " ORDER BY " + KEY_BL_ADDED_TIME + " DESC";

            Cursor cursor = db.rawQuery(query, null);
            if (cursor.moveToFirst()) {
                do {
                    entries.add(new BlacklistEntry(cursor));
                } while (cursor.moveToNext());
            }
            cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "Error reading blacklist", e);
        }
        return entries;
    }

    public static boolean setBlacklistEnabled(String domain, boolean enabled) {
        try {
            SQLiteDatabase db = getInstance().getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(KEY_BL_ENABLED, enabled ? 1 : 0);

            int updated = db.update(TABLE_BLACKLIST, values,
                    KEY_BL_DOMAIN + " = ?",
                    new String[]{domain.toLowerCase()});

            if (updated > 0) {
                if (enabled) blacklistCache.add(domain.toLowerCase());
                else blacklistCache.remove(domain.toLowerCase());
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error updating blacklist", e);
            return false;
        }
    }

    private void refreshBlacklistCache() {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Set<String> newCache = new HashSet<>();

            String query = "SELECT " + KEY_BL_DOMAIN + " FROM " + TABLE_BLACKLIST +
                    " WHERE " + KEY_BL_ENABLED + " = 1";

            Cursor cursor = db.rawQuery(query, null);
            if (cursor.moveToFirst()) {
                do {
                    newCache.add(cursor.getString(0));
                } while (cursor.moveToNext());
            }
            cursor.close();

            blacklistCache = newCache;
            lastCacheUpdate = System.currentTimeMillis();

            Log.d(TAG, "Blacklist cache refreshed: " + blacklistCache.size() + " entries");
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing blacklist cache", e);
        }
    }

    // ==================== DATA CLASSES ====================

    public static class TrafficRecord {
        public long id;
        public long timestamp;
        public String sourceIp;
        public int sourcePort;
        public String destIp;
        public int destPort;
        public String protocol;
        public String direction;
        public int size;
        public String appName;
        public String packageName;
        public int uid;
        public String payload;
        public String domain;

        public TrafficRecord(Cursor cursor) {
            this.id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ID));
            this.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_TIMESTAMP));
            this.sourceIp = cursor.getString(cursor.getColumnIndexOrThrow(KEY_SOURCE_IP));
            this.sourcePort = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_SOURCE_PORT));
            this.destIp = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DEST_IP));
            this.destPort = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_DEST_PORT));
            this.protocol = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PROTOCOL));
            this.direction = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DIRECTION));
            this.size = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_SIZE));
            this.appName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_APP_NAME));
            this.packageName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PACKAGE_NAME));
            this.uid = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_UID));
            this.payload = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PAYLOAD));
            this.domain = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DOMAIN));
        }
    }

    public static class BlacklistEntry {
        public long id;
        public String domain;
        public long addedTime;
        public boolean enabled;
        public String description;

        public BlacklistEntry(Cursor cursor) {
            this.id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_BL_ID));
            this.domain = cursor.getString(cursor.getColumnIndexOrThrow(KEY_BL_DOMAIN));
            this.addedTime = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_BL_ADDED_TIME));
            this.enabled = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_BL_ENABLED)) == 1;
            this.description = cursor.getString(cursor.getColumnIndexOrThrow(KEY_BL_DESCRIPTION));
        }
    }

    // ==================== LEGACY ====================

    public Allowed() {
        super(null, DATABASE_NAME, null, DATABASE_VERSION);
    }

    public Allowed(String raddr, int rport) {
        this();
        this.raddr = raddr;
        this.rport = rport;
    }

    public boolean isForwarded() {
        return raddr != null;
    }
    private static final Set<String> blockedIPs = ConcurrentHashMap.newKeySet();

// Call this once on startup
/*public static void loadBlacklist(Context context) {
    try {
        SQLiteDatabase db = getInstance().getReadableDatabase();
        Cursor c = db.rawQuery("SELECT domain FROM blacklist WHERE enabled=1", null);
        blockedIPs.clear();
        while (c.moveToNext()) {
            String entry = c.getString(0).trim();
            blockedIPs.add(entry);
            // also resolve to IPs for faster blocking
            try {
                InetAddress[] addrs = InetAddress.getAllByName(entry);
                for (InetAddress addr : addrs) {
                    blockedIPs.add(addr.getHostAddress());
                }
            } catch (Exception ignore) {}
        }
        c.close();
        Log.d("Allowed", "Loaded blacklist entries: " + blockedIPs.size());
    } catch (Exception e) {
        Log.e("Allowed", "Error loading blacklist: " + e.getMessage());
    }
}*/
// ✅ Returns all currently blocked domains/IPs for use in ToyVpnService
public static Set<String> getBlockedEntries() {
    return new HashSet<>(blockedIPs); // blockedIPs is your in-memory cache
}

    public static void loadBlacklist(Context context) {
    try {
        SQLiteDatabase db = getInstance().getReadableDatabase();
        Cursor c = db.rawQuery("SELECT domain FROM blacklist WHERE enabled=1", null);
        blockedIPs.clear();

        while (c.moveToNext()) {
            String entry = c.getString(0).trim().toLowerCase();

            // ✅ Store exact domain
            blockedIPs.add(entry);

            // ✅ Also store wildcard pattern for subdomains
            if (!entry.startsWith(".")) {
                blockedIPs.add("." + entry);
            }

            // ✅ Resolve and store all IPs for this domain
            try {
                InetAddress[] addrs = InetAddress.getAllByName(entry);
                for (InetAddress addr : addrs) {
                    blockedIPs.add(addr.getHostAddress());
                }
            } catch (Exception ignore) {}
        }
        c.close();

        Log.d("Allowed", "✅ Loaded blacklist entries: " + blockedIPs.size());
    } catch (Exception e) {
        Log.e("Allowed", "❌ Error loading blacklist: " + e.getMessage());
    }
}


   /* public static boolean isDomainBlacklisted(String domainOrIp) {
    return blockedIPs.contains(domainOrIp);
}*/
   public static boolean isDomainBlacklisted(String domainOrIp) {
       if (domainOrIp == null || domainOrIp.isEmpty()) return false;
       domainOrIp = domainOrIp.toLowerCase();

       // ✅ Exact match for domain or IP
       if (blockedIPs.contains(domainOrIp)) return true;

       // ✅ Match subdomains (like m.facebook.com → facebook.com)
       for (String blocked : blockedIPs) {
           if (blocked.startsWith(".")) {
               String root = blocked.substring(1);
               if (domainOrIp.endsWith("." + root) || domainOrIp.equals(root)) {
                   return true;
               }
           }
       }

       return false;
   }


    // When user adds a new domain via UI
public static boolean addToBlacklist(String domain) {
    boolean result = addToBlacklist(domain, ""); // existing DB insert
    if (result) {
        // also update the in-memory IP cache
        blockedIPs.add(domain);
        try {
            InetAddress[] addrs = InetAddress.getAllByName(domain);
            for (InetAddress addr : addrs) blockedIPs.add(addr.getHostAddress());
        } catch (Exception ignore) {}
    }
    return result;
}


}
