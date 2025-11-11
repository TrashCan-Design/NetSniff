package com.netsniff.app;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.JSArray;

@CapacitorPlugin(name = "ToyVpn")
public class ToyVpnPlugin extends Plugin {
    private static final String TAG = "ToyVpnPlugin";
    public static final int REQUEST_VPN_PERMISSION = 101;
    public static ToyVpnPlugin instance;
    private boolean hasVpnPermission = false;
    private PluginCall pendingPermissionCall;

    @Override
    public void load() {
        Log.d(TAG, "ToyVpnPlugin loaded");
        instance = this;
    }

    @PluginMethod
    public void requestVpnPermission(PluginCall call) {
        Log.d(TAG, "requestVpnPermission called");
        
        try {
            Intent intent = VpnService.prepare(getContext());
            
            if (intent != null) {
                Log.d(TAG, "VPN permission required, launching system dialog");
                pendingPermissionCall = call;
                startActivityForResult(call, intent, REQUEST_VPN_PERMISSION);
            } else {
                Log.d(TAG, "VPN permission already granted");
                hasVpnPermission = true;
                JSObject result = new JSObject();
                result.put("status", "permission_granted");
                call.resolve(result);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting VPN permission", e);
            call.reject("Failed to request VPN permission: " + e.getMessage());
        }
    }
    
    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_VPN_PERMISSION && pendingPermissionCall != null) {
            if (resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "VPN permission granted");
                hasVpnPermission = true;
                JSObject result = new JSObject();
                result.put("status", "permission_granted");
                pendingPermissionCall.resolve(result);
            } else {
                Log.d(TAG, "VPN permission denied");
                hasVpnPermission = false;
                pendingPermissionCall.reject("VPN permission denied by user");
            }
            pendingPermissionCall = null;
        }
    }
    
    @PluginMethod
    public void startVpn(PluginCall call) {
        Log.d(TAG, "startVpn called");
        
        Intent intent = VpnService.prepare(getContext());
        if (intent != null && !hasVpnPermission) {
            JSObject result = new JSObject();
            result.put("status", "permission_required");
            result.put("message", "Please request VPN permission first");
            call.resolve(result);
            return;
        }
        
        try {
            Intent vpnIntent = new Intent(getContext(), ToyVpnService.class);
            vpnIntent.setAction(ToyVpnService.ACTION_CONNECT);
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                getContext().startForegroundService(vpnIntent);
            } else {
                getContext().startService(vpnIntent);
            }
            
            JSObject result = new JSObject();
            result.put("status", "started");
            call.resolve(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN", e);
            call.reject("Error starting VPN service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopVpn(PluginCall call) {
        Log.d(TAG, "stopVpn called");
        
        try {
            Intent vpnIntent = new Intent(getContext(), ToyVpnService.class);
            vpnIntent.setAction(ToyVpnService.ACTION_DISCONNECT);
            getContext().startService(vpnIntent);
            
            JSObject result = new JSObject();
            result.put("status", "stopping");
            call.resolve(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping VPN", e);
            call.reject("Error stopping VPN: " + e.getMessage());
        }
    }

    public static void notifyPacketCaptured(JSObject packetData) {
        if (instance != null) {
            try {
                instance.notifyListeners("packetCaptured", packetData);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying packet listeners", e);
            }
        }
    }
    
    public static void notifyPacketBatch(JSObject batchData) {
        if (instance != null) {
            try {
                instance.notifyListeners("packetBatch", batchData);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying batch listeners", e);
            }
        }
    }

    public static void notifyVpnStopped() {
        if (instance != null) {
            try {
                JSObject data = new JSObject();
                instance.notifyListeners("vpnStopped", data);
                Log.d(TAG, "VPN stopped event sent");
            } catch (Exception e) {
                Log.e(TAG, "Error in notifyVpnStopped", e);
            }
        }
    }

    @PluginMethod
    public void getTraffic(PluginCall call) {
        try {
            int limit = call.getInt("limit", 100);
            java.util.List<Allowed.TrafficRecord> list = Allowed.getAllTraffic(limit);

            JSArray arr = new JSArray();
            for (Allowed.TrafficRecord r : list) {
                JSObject o = new JSObject();
                o.put("id", r.id);
                o.put("timestamp", r.timestamp);
                o.put("source_ip", r.sourceIp);
                o.put("source_port", r.sourcePort);
                o.put("dest_ip", r.destIp);
                o.put("dest_port", r.destPort);
                o.put("protocol", r.protocol);
                o.put("direction", r.direction);
                o.put("size", r.size);
                o.put("app_name", r.appName);
                o.put("package_name", r.packageName);
                o.put("uid", r.uid);
                o.put("payload", r.payload);
                o.put("domain", r.domain);
                arr.put(o);
            }

            JSObject res = new JSObject();
            res.put("traffic", arr);
            call.resolve(res);
        } catch (Exception e) {
            Log.e(TAG, "Error in getTraffic", e);
            call.reject("Failed to get traffic: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getBlacklist(PluginCall call) {
        try {
            java.util.List<Allowed.BlacklistEntry> list = Allowed.getAllBlacklist();
            JSArray arr = new JSArray();
            for (Allowed.BlacklistEntry b : list) {
                JSObject o = new JSObject();
                o.put("id", b.id);
                o.put("domain", b.domain);
                o.put("added_time", b.addedTime);
                o.put("enabled", b.enabled);
                o.put("description", b.description);
                arr.put(o);
            }
            JSObject res = new JSObject();
            res.put("blacklist", arr);
            call.resolve(res);
        } catch (Exception e) {
            Log.e(TAG, "Error in getBlacklist", e);
            call.reject("Failed to get blacklist: " + e.getMessage());
        }
    }

    @PluginMethod
    public void addToBlacklist(PluginCall call) {
        String domain = call.getString("domain", "").trim();
        String description = call.getString("description", "");
        if (domain.isEmpty()) {
            call.reject("domain required");
            return;
        }
        boolean ok = Allowed.addToBlacklist(domain, description);
        JSObject res = new JSObject();
        res.put("ok", ok);
        call.resolve(res);
    }

    @PluginMethod
    public void removeFromBlacklist(PluginCall call) {
        String domain = call.getString("domain", "").trim();
        if (domain.isEmpty()) {
            call.reject("domain required");
            return;
        }
        boolean ok = Allowed.removeFromBlacklist(domain);
        JSObject res = new JSObject();
        res.put("ok", ok);
        call.resolve(res);
    }

    @PluginMethod
    public void setBlacklistEnabled(PluginCall call) {
        String domain = call.getString("domain", "").trim();
        boolean enabled = call.getBoolean("enabled", true);
        if (domain.isEmpty()) {
            call.reject("domain required");
            return;
        }
        boolean ok = Allowed.setBlacklistEnabled(domain, enabled);
        JSObject res = new JSObject();
        res.put("ok", ok);
        call.resolve(res);
    }

    @PluginMethod
    public void getSavedTraffic(PluginCall call) {
        try {
            try {
                Allowed.getInstance();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Allowed not initialized, initializing now");
                Allowed.initialize(getContext());
            }

            java.util.List<Allowed.TrafficRecord> list = Allowed.getOldTraffic();

            JSArray arr = new JSArray();
            for (Allowed.TrafficRecord r : list) {
                JSObject o = new JSObject();
                o.put("id", r.id);
                o.put("timestamp", r.timestamp);
                o.put("source_ip", r.sourceIp);
                o.put("source_port", r.sourcePort);
                o.put("dest_ip", r.destIp);
                o.put("dest_port", r.destPort);
                o.put("protocol", r.protocol);
                o.put("direction", r.direction);
                o.put("size", r.size);
                o.put("app_name", r.appName);
                o.put("package_name", r.packageName);
                o.put("uid", r.uid);
                o.put("payload", r.payload);
                o.put("domain", r.domain);
                arr.put(o);
            }

            JSObject ret = new JSObject();
            ret.put("traffic", arr);
            call.resolve(ret);

            Log.d(TAG, "Returned " + list.size() + " saved packets from DB");

        } catch (Exception e) {
            Log.e(TAG, "Error in getSavedTraffic", e);
            call.reject("Failed to fetch saved traffic: " + e.getMessage());
        }
    }
}