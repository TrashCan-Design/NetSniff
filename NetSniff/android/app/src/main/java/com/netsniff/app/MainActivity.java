package com.netsniff.app;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "MainActivity";
    
    // Register plugin in constructor - BEFORE Capacitor loads
    public MainActivity() {
        super();
        registerPlugin(ToyVpnPlugin.class);
        Log.d(TAG, "ToyVpnPlugin registered in constructor");
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "MainActivity onCreate starting");
        super.onCreate(savedInstanceState);
        Log.d(TAG, "MainActivity onCreate completed");
        
        // Check for Usage Stats permission on startup
        checkUsageStatsPermission();
    }
    
    /**
     * Check if Usage Stats permission is granted (from reference code)
     */
    private boolean hasUsageStatsPermission() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(
                    "android:get_usage_stats",
                    android.os.Process.myUid(),
                    getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Log.e(TAG, "Error checking usage stats permission", e);
            return false;
        }
    }
    
    /**
     * Check and request Usage Stats permission if not granted
     */
    private void checkUsageStatsPermission() {
        if (!hasUsageStatsPermission()) {
            showUsageStatsPermissionDialog();
        } else {
            Log.d(TAG, "Usage Stats permission already granted");
        }
    }
    
    /**
     * Show dialog explaining why Usage Stats permission is needed
     */
    private void showUsageStatsPermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Usage Access Permission Required")
            .setMessage("NetSniff needs Usage Access permission to identify which apps are generating network traffic.\n\n" +
                       "This permission allows the app to map network connections to specific applications.\n\n" +
                       "Please grant 'Usage Access' permission in the next screen.")
            .setPositiveButton("Grant Permission", (dialog, which) -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Error opening usage stats settings", e);
                    Toast.makeText(this, "Unable to open settings", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Skip", (dialog, which) -> {
                Toast.makeText(this, 
                    "App identification may not work properly without this permission", 
                    Toast.LENGTH_LONG).show();
            })
            .setCancelable(false)
            .show();
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // Re-check permission when returning to the app (user might have granted it)
        if (hasUsageStatsPermission()) {
            Log.d(TAG, "Usage Stats permission is now granted");
        }
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "MainActivity onDestroy - ensuring VPN is stopped");
        
        try {
            Intent stopIntent = new Intent(this, ToyVpnService.class);
            stopIntent.setAction(ToyVpnService.ACTION_DISCONNECT);
            startService(stopIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping VPN on destroy", e);
        }
        
        super.onDestroy();
    }
}