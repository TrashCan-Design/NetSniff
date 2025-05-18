package com.netsniff.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.view.WindowManager;
import android.util.Log;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.Plugin;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "MainActivity";
    private final Executor backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "MainActivity onCreate starting");
        super.onCreate(savedInstanceState);
        
        // Set window flags immediately for better performance
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );
        
        // Move StrictMode initialization to background thread
        if (BuildConfig.DEBUG && BuildConfig.ENABLE_STRICT_MODE) {
            backgroundExecutor.execute(this::enableStrictMode);
        }
        
        // Register plugins manually
        try {
            Log.d(TAG, "Attempting to register ToyVpnPlugin");
            registerPlugin(ToyVpnPlugin.class);
            Log.d(TAG, "ToyVpnPlugin registered successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error registering ToyVpnPlugin", e);
        }
        
        // List all available plugins for debugging
        /*try {
            Log.d(TAG, "Available plugins in Capacitor:");
            for (Class<? extends Plugin> plugin : getRegisteredPlugins()) {
                Log.d(TAG, "  - " + plugin.getName());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error listing plugins", e);
        }*/
        
        // We don't need to call init() here as it's called in the superclass constructor
        /*this.init(savedInstanceState, new ArrayList<Class<? extends Plugin>>() {{
            // ToyVpnPlugin is already registered above
        }});*/
    }
    
    @Override
    public void onStart() {
        super.onStart();
        
        // Use our custom plugin initializer after the bridge is ready
        try {
            Log.d(TAG, "Manually initializing ToyVpn plugin");
            PluginInitializer.initializePlugins(this.getBridge());
            Log.d(TAG, "Manual plugin initialization complete");
        } catch (Exception e) {
            Log.e(TAG, "Error during manual plugin initialization", e);
        }
    }

    private void enableStrictMode() {
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
            .build());

        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .detectActivityLeaks()
            .penaltyLog()
            .build());
    }

    @Override
    public void onResume() {
        super.onResume();
        // Ensure WebView is ready - defer to next frame for better UI responsiveness
        mainHandler.post(() -> {
            if (getBridge() != null && getBridge().getWebView() != null) {
                getBridge().getWebView().resumeTimers();
            }
        });
    }

    @Override
    public void onPause() {
        // Pause WebView when not visible - with null checks for safety
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().pauseTimers();
        }
        super.onPause();
    }
    
    @Override
    public void onDestroy() {
        Log.d("MainActivity", "MainActivity onDestroy: Ensuring VPN is stopped before app is destroyed");
        
        // Double-check to ensure the VPN service is stopped when the app is destroyed
        try {
            android.content.Intent vpnServiceIntent = new android.content.Intent(this, ToyVpnService.class);
            Log.d("MainActivity", "Attempting to stop ToyVpnService from onDestroy");
            stopService(vpnServiceIntent);
            
            // Try to notify the JS layer through static method if possible
            try {
                ToyVpnPlugin.notifyVpnStopped();
                Log.d("MainActivity", "Notified JS layer about VPN shutdown from onDestroy");
            } catch (Exception e) {
                Log.e("MainActivity", "Could not notify JS layer about VPN shutdown", e);
            }
            
            // If we have a running ToyVpnPlugin instance, try to use it as well
            if (ToyVpnPlugin.instance != null) {
                Log.d("MainActivity", "Found ToyVpnPlugin instance, making direct call to stop VPN");
                try {
                    // Call the helper method to stop via the plugin
                    ToyVpnPlugin.instance.directStopVpn();
                    Log.d("MainActivity", "Successfully called directStopVpn through instance");
                } catch (Exception e) {
                    Log.e("MainActivity", "Error stopping VPN through plugin instance", e);
                }
            } else {
                Log.d("MainActivity", "No ToyVpnPlugin instance available for direct stopping");
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error stopping VPN service on app destruction", e);
        }
        
        super.onDestroy();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);
        
        // Handle VPN permission result through our plugin's custom method
        if (requestCode == ToyVpnPlugin.REQUEST_VPN_PERMISSION) {
            Log.d(TAG, "Received VPN permission result, forwarding to ToyVpnPlugin");
            try {
                // Get the plugin instance directly from the static field
                ToyVpnPlugin toyVpnPlugin = ToyVpnPlugin.instance;
                if (toyVpnPlugin != null) {
                    toyVpnPlugin.handlePermissionResult(resultCode);
                    Log.d(TAG, "Successfully forwarded permission result to ToyVpnPlugin");
                } else {
                    Log.e(TAG, "ToyVpnPlugin instance is null, cannot handle VPN permission result");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling VPN permission result", e);
            }
        }
    }
}
