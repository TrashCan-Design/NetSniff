package com.netsniff.app;

import android.content.Intent;
import android.net.VpnService;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "ToyVpnPlugin")
public class ToyVpnPlugin extends Plugin {
    private static final String TAG = "ToyVpnPlugin";
    private static final int REQUEST_VPN_PERMISSION = 1234;
    private Intent vpnServiceIntent;
    
    // Static reference to the current plugin instance for notifying from the service
    private static ToyVpnPlugin instance;

    @Override
    public void load() {
        super.load();
        instance = this;
    }

    // Method for the VPN service to notify about captured packets
    public static void notifyPacketCaptured(JSObject packetData) {
        if (instance != null) {
            instance.notifyListeners("packetCaptured", packetData);
        }
    }

    @PluginMethod
    public void startVpn(PluginCall call) {
        try {
            Log.d(TAG, "Starting VPN Service");
            Intent intent = VpnService.prepare(getActivity());
            
            if (intent != null) {
                // We need to ask for VPN permission
                saveCall(call);
                getActivity().startActivityForResult(intent, REQUEST_VPN_PERMISSION);
            } else {
                // Permission already granted, start the VPN
                startVpnService(call);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN", e);
            call.reject("Error starting VPN: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void stopVpn(PluginCall call) {
        try {
            Log.d(TAG, "Stopping VPN Service");
            
            if (vpnServiceIntent != null) {
                getActivity().stopService(vpnServiceIntent);
                vpnServiceIntent = null;
                
                // Notify the app that VPN has stopped
                JSObject result = new JSObject();
                result.put("status", "stopped");
                call.resolve(result);
            } else {
                JSObject result = new JSObject();
                result.put("status", "not_running");
                call.resolve(result);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping VPN", e);
            call.reject("Error stopping VPN: " + e.getMessage(), e);
        }
    }

    private void startVpnService(PluginCall call) {
        try {
            // Start the VPN service
            vpnServiceIntent = new Intent(getActivity(), ToyVpnService.class);
            
            // Pass additional parameters if needed
            String serverAddress = call.getString("serverAddress", "127.0.0.1");
            String serverPort = call.getString("serverPort", "8000");
            String sharedSecret = call.getString("sharedSecret", "shared_secret");
            
            vpnServiceIntent.putExtra(ToyVpnService.EXTRA_SERVER_ADDRESS, serverAddress);
            vpnServiceIntent.putExtra(ToyVpnService.EXTRA_SERVER_PORT, serverPort);
            vpnServiceIntent.putExtra(ToyVpnService.EXTRA_SHARED_SECRET, sharedSecret);
            
            getActivity().startService(vpnServiceIntent);
            
            JSObject result = new JSObject();
            result.put("status", "started");
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Error in startVpnService", e);
            call.reject("Error starting VPN service: " + e.getMessage(), e);
        }
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_VPN_PERMISSION) {
            PluginCall savedCall = getSavedCall();
            
            if (savedCall == null) {
                Log.e(TAG, "No stored plugin call for VPN permission request");
                return;
            }
            
            if (resultCode == android.app.Activity.RESULT_OK) {
                startVpnService(savedCall);
            } else {
                savedCall.reject("VPN permission denied by user");
            }
            
            releaseCall(savedCall);
        }
    }
}
