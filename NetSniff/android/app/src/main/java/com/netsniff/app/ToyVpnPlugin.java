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
}