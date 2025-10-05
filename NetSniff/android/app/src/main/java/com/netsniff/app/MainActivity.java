package com.netsniff.app;

import android.os.Bundle;
import android.util.Log;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "MainActivity";
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerPlugin(ToyVpnPlugin.class);
        Log.d(TAG, "MainActivity created and ToyVpnPlugin registered");
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "MainActivity onDestroy - ensuring VPN is stopped");
        
        // Gracefully stop VPN if running
        try {
            android.content.Intent stopIntent = new android.content.Intent(this, ToyVpnService.class);
            stopIntent.setAction(ToyVpnService.ACTION_DISCONNECT);
            startService(stopIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping VPN on destroy", e);
        }
        
        super.onDestroy();
    }
}