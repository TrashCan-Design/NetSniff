package com.netsniff.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import android.net.VpnService;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONObject;
import org.json.JSONException;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import androidx.activity.result.ActivityResult;

@CapacitorPlugin(name = "ToyVpn")
public class ToyVpnPlugin extends Plugin {
    private static final String TAG = "ToyVpnPlugin";
    public static final int REQUEST_VPN_PERMISSION = 101;
    private Intent vpnServiceIntent;
    public static ToyVpnPlugin instance;
    private boolean hasVpnPermission = false;

    @Override
    public void load() {
        Log.d(TAG, "ToyVpnPlugin loading...");
        instance = this;
        
        // Expose a JavaScript interface to the WebView
        try {
            if (getBridge() != null && getBridge().getWebView() != null) {
                WebView webView = getBridge().getWebView();
                ToyVpnJSInterface jsInterface = new ToyVpnJSInterface();
                webView.addJavascriptInterface(jsInterface, "ToyVpnNative");
                
                // Inject a script to make the native plugin available
                String js = "window.ToyVpnNative = window.ToyVpnNative || {};" +
                           "window.Capacitor = window.Capacitor || {};" +
                           "window.Capacitor.Plugins = window.Capacitor.Plugins || {};" +
                           "window.ToyVpn = {" +  
                           "  requestVpnPermission: function() { return JSON.parse(ToyVpnNative.requestVpnPermission()); }," +
                           "  startVpn: function(options) { return JSON.parse(ToyVpnNative.startVpn(JSON.stringify(options || {}))); }," +
                           "  stopVpn: function() { return JSON.parse(ToyVpnNative.stopVpn()); }," +
                           "  addListener: function(eventName, callback) { " +
                           "    ToyVpnNative.addListener(eventName);" +
                           "    window.ToyVpnNative._listeners = window.ToyVpnNative._listeners || {};" +
                           "    window.ToyVpnNative._listeners[eventName] = callback;" +
                           "    return { remove: function() { return Promise.resolve(); } };" +
                           "  }," +
                           "  removeAllListeners: function() { " +
                           "    window.ToyVpnNative._listeners = {};" +
                           "    return JSON.parse(ToyVpnNative.removeAllListeners());" +
                           "  }" +
                           "};" +
                           "window.Capacitor.Plugins.ToyVpn = window.ToyVpn;" +  
                           "console.log('ToyVpn native plugin successfully injected into window.Capacitor.Plugins');" +
                           "window.ToyVpnNative.isAvailable = true;" +
                           "window.dispatchToyVpnEvent = function(eventName, data) {" +
                           "  if (window.ToyVpnNative && window.ToyVpnNative._listeners && window.ToyVpnNative._listeners[eventName]) {" +
                           "    console.log('Dispatching ToyVpn event: ' + eventName);" +
                           "    window.ToyVpnNative._listeners[eventName](data || {});" +
                           "  }" +
                           "};";
                
                webView.post(() -> {
                    webView.evaluateJavascript(js, null);
                    webView.postDelayed(() -> webView.evaluateJavascript(js, null), 1000);
                    webView.postDelayed(() -> webView.evaluateJavascript(js, null), 3000);
                });
                
                Log.d(TAG, "JavaScript interface exposed successfully");
            } else {
                Log.e(TAG, "Could not get WebView from Bridge");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error exposing JavaScript interface", e);
        }
        
        Log.d(TAG, "ToyVpnPlugin loaded successfully");
    }

    public static void notifyPacketCaptured(JSObject packetData) {
        if (instance != null) {
            Log.d(TAG, "Notifying packet captured: " + packetData.toString());
            
            try {
                final JSObject finalPacketData = packetData;
                
                if (instance.getActivity() != null) {
                    instance.getActivity().runOnUiThread(() -> {
                        try {
                            instance.notifyListeners("packetCaptured", finalPacketData);
                            
                            if (instance.getBridge() != null && instance.getBridge().getWebView() != null) {
                                String js = "if (window.dispatchToyVpnEvent) { window.dispatchToyVpnEvent('packetCaptured', " + 
                                        finalPacketData.toString() + "); }";
                                instance.getBridge().getWebView().evaluateJavascript(js, null);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error notifying packet listeners", e);
                        }
                    });
                } else {
                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                    mainHandler.post(() -> {
                        try {
                            instance.notifyListeners("packetCaptured", finalPacketData);
                        } catch (Exception e) {
                            Log.e(TAG, "Error notifying packet listeners via handler", e);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in notify process", e);
            }
        } else {
            Log.e(TAG, "Cannot notify packet captured: plugin instance is null");
        }
    }

    @PluginMethod
    public void requestVpnPermission(PluginCall call) {
        Log.d(TAG, "requestVpnPermission called");
        try {
            Intent intent = VpnService.prepare(getActivity());
            if (intent != null) {
                Log.d(TAG, "VPN permission required, launching system dialog");
                saveCall(call);
                getActivity().startActivityForResult(intent, REQUEST_VPN_PERMISSION);
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
    
    @PluginMethod
    public void startVpn(PluginCall call) {
        Log.d(TAG, "startVpn called");
        
        // Check if we already have permission
        Intent intent = VpnService.prepare(getActivity());
        if (intent != null && !hasVpnPermission) {
            // We still need permission, this shouldn't happen if flow is followed correctly
            Log.w(TAG, "VPN permission required but not requested first");
            JSObject result = new JSObject();
            result.put("status", "permission_required");
            result.put("message", "Please request VPN permission first");
            call.resolve(result);
            return;
        }
        
        // Start the VPN service
        startVpnService(call);
    }
    
    public void handlePermissionResult(int resultCode) {
        Log.d(TAG, "handlePermissionResult: resultCode=" + resultCode);
        PluginCall savedCall = getSavedCall();
        
        if (savedCall == null) {
            Log.e(TAG, "No saved call to resolve VPN permission result");
            return;
        }
        
        if (resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "VPN permission granted by user");
            hasVpnPermission = true;
            JSObject result = new JSObject();
            result.put("status", "permission_granted");
            savedCall.resolve(result);
        } else {
            Log.d(TAG, "VPN permission denied by user");
            hasVpnPermission = false;
            savedCall.reject("VPN permission denied by user");
        }
    }

    /**
     * Check if the VPN service is currently running
     * @return true if the service is running, false otherwise
     */
    private boolean isVpnServiceRunning() {
        ActivityManager activityManager = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (ToyVpnService.class.getName().equals(service.service.getClassName())) {
                Log.d(TAG, "Found VPN service running");
                return true;
            }
        }
        Log.d(TAG, "VPN service not found in running services");
        return false;
    }

    @PluginMethod
    public void stopVpn(PluginCall call) {
        try {
            Log.d(TAG, "EXTREME VPN TERMINATION: Stopping VPN service with maximum force");
            
            // First immediately notify frontend that VPN is stopping - this will clear UI state
            notifyListeners("vpnStopping", new JSObject());
            
            // Save the plugin call to resolve later
            final PluginCall savedCall = call;
            
            // Create a thread to handle VPN stopping to avoid blocking UI
            Thread stopThread = new Thread(() -> {
                boolean stoppedSuccessfully = false;
                int attempts = 0;
                final int MAX_ATTEMPTS = 3;  // Reduced for faster response
                
                // First immediately try to kill any VPN service threads
                try {
                    Log.d(TAG, "EXTREME VPN TERMINATION: Pre-emptively identifying and interrupting VPN threads");
                    Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
                    for (Thread thread : threadSet) {
                        if (thread.getName().contains("VPN") || 
                            thread.getName().contains("Toy") || 
                            thread.getName().contains("network") || 
                            thread.getName().contains("packet")) {
                            
                            Log.d(TAG, "EXTREME VPN TERMINATION: Interrupting thread: " + thread.getName());
                            thread.interrupt();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error interrupting VPN threads", e);
                }
                
                while (!stoppedSuccessfully && attempts < MAX_ATTEMPTS) {
                    attempts++;
                    Log.d(TAG, "EXTREME VPN TERMINATION: Attempt " + attempts + " to stop VPN service");
                    
                    // First, try to send the disconnect action aggressively with multiple approaches
                    try {
                        // Application context approach
                        Context appContext = getContext().getApplicationContext();
                        Intent intent = new Intent(appContext, ToyVpnService.class);
                        intent.setAction(ToyVpnService.ACTION_DISCONNECT);
                        appContext.startService(intent);
                        Log.d(TAG, "EXTREME VPN TERMINATION: Sent disconnect action via application context");
                        
                        // Direct context approach
                        intent = new Intent(getContext(), ToyVpnService.class);
                        intent.setAction(ToyVpnService.ACTION_DISCONNECT);
                        getContext().startService(intent);
                        Log.d(TAG, "EXTREME VPN TERMINATION: Sent disconnect action via direct context");
                        
                        // Activity context approach if available
                        if (getActivity() != null) {
                            intent = new Intent(getActivity(), ToyVpnService.class);
                            intent.setAction(ToyVpnService.ACTION_DISCONNECT);
                            getActivity().startService(intent);
                            Log.d(TAG, "EXTREME VPN TERMINATION: Sent disconnect action via activity context");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending disconnect action", e);
                    }
                    
                    // Shorter wait between actions for faster response
                    try { Thread.sleep(100); } catch (InterruptedException e) { /* ignore */ }
                    
                    // Immediate stop attempts with multiple approaches
                    try {
                        // Application context stop
                        Context appContext = getContext().getApplicationContext();
                        Intent intent = new Intent(appContext, ToyVpnService.class);
                        boolean stopped = appContext.stopService(intent);
                        Log.d(TAG, "EXTREME VPN TERMINATION: App context stopService result: " + stopped);
                        if (stopped) stoppedSuccessfully = true;
                        
                        // Direct context stop
                        intent = new Intent(getContext(), ToyVpnService.class);
                        stopped = getContext().stopService(intent);
                        Log.d(TAG, "EXTREME VPN TERMINATION: Direct stopService result: " + stopped);
                        if (stopped) stoppedSuccessfully = true;
                        
                        // Activity context stop if available
                        if (getActivity() != null) {
                            intent = new Intent(getActivity(), ToyVpnService.class);
                            stopped = getActivity().stopService(intent);
                            Log.d(TAG, "EXTREME VPN TERMINATION: Activity stopService result: " + stopped);
                            if (stopped) stoppedSuccessfully = true;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping service directly", e);
                    }
                    
                    // Try to directly find and kill the service
                    try {
                        ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
                        List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(Integer.MAX_VALUE);
                        
                        for (ActivityManager.RunningServiceInfo service : services) {
                            if (ToyVpnService.class.getName().equals(service.service.getClassName())) {
                                Log.d(TAG, "EXTREME VPN TERMINATION: Found running VPN service with pid: " + service.pid);
                                // Try to forcefully stop it
                                try {
                                    Log.d(TAG, "EXTREME VPN TERMINATION: Attempting to kill process with pid: " + service.pid);
                                    android.os.Process.killProcess(service.pid);
                                    // Add a small delay to let the system process the kill command
                                    Thread.sleep(200);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error killing process: " + service.pid, e);
                                }
                                stoppedSuccessfully = true;
                                break;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error killing service process", e);
                    }
                    
                    // Shorter wait between attempts for faster response
                    try { Thread.sleep(200); } catch (InterruptedException e) { /* ignore */ }
                }
                
                // Final nuclear option - try to forcefully stop anything VPN related
                try {
                    Log.d(TAG, "EXTREME VPN TERMINATION: Executing nuclear cleanup option");
                    
                    // Forcefully stop any services with our package name
                    ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
                    List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(Integer.MAX_VALUE);
                    
                    for (ActivityManager.RunningServiceInfo service : services) {
                        String className = service.service.getClassName();
                        if (className.contains("netsniff") || className.contains("vpn") || className.contains("toy")) {
                            Log.d(TAG, "EXTREME VPN TERMINATION: Found potentially related service: " + className + " with pid: " + service.pid);
                            try {
                                android.os.Process.killProcess(service.pid);
                                Log.d(TAG, "EXTREME VPN TERMINATION: Killed process with pid: " + service.pid);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to kill process with pid: " + service.pid, e);
                            }
                        }
                    }
                    
                    // Create a special intent to explicitly stop and kill the VPN
                    final Intent killIntent = new Intent(getContext(), ToyVpnService.class);
                    killIntent.setAction("com.netsniff.app.KILL"); // Special action to perform final cleanup
                    getContext().startService(killIntent);
                    getContext().stopService(killIntent);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error in nuclear cleanup option", e);
                }
                
                // Now we'll always tell the UI that VPN is stopped, regardless of whether it actually stopped
                // Create final copies of the variables for use in the lambda
                final boolean finalStoppedSuccessfully = stoppedSuccessfully;
                final int finalAttempts = attempts;
                
                // Force a small delay to ensure all cleanup operations have had time to execute
                try { Thread.sleep(300); } catch (InterruptedException e) { /* ignore */ }
                
                // Reset the vpnServiceIntent to ensure it's not reused
                vpnServiceIntent = null;
                
                // Use bridge.executeOnMainThread to safely call UI methods
                getBridge().executeOnMainThread(() -> {
                    try {
                        Log.d(TAG, "EXTREME VPN TERMINATION: Executing final cleanup on main thread");
                        
                        // Make one final attempt to stop the service from the main thread
                        if (getActivity() != null) {
                            Intent finalIntent = new Intent(getActivity(), ToyVpnService.class);
                            getActivity().stopService(finalIntent);
                        }
                        
                        // Explicitly notify the UI that VPN has stopped
                        notifyListeners("vpnStopped", new JSObject());
                        Log.d(TAG, "EXTREME VPN TERMINATION: Notified listeners that VPN is stopped");
                        
                        // Resolve the plugin call on the main thread
                        JSObject ret = new JSObject();
                        ret.put("status", "stopped");
                        ret.put("success", finalStoppedSuccessfully);
                        ret.put("attempts", finalAttempts);
                        call.resolve(ret);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in final VPN termination steps", e);
                        // Still try to resolve the call even if there was an error
                        try {
                            JSObject ret = new JSObject();
                            ret.put("status", "stopped");
                            ret.put("success", false);
                            ret.put("error", e.getMessage());
                            call.resolve(ret);
                        } catch (Exception ex) {
                            Log.e(TAG, "Failed to resolve call after error", ex);
                        }
                    }
                });
            });
            
            // Set as daemon to avoid blocking app shutdown
            stopThread.setDaemon(true);
            stopThread.start();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in stopVpn", e);
            // Still mark as stopped even on error
            JSObject ret = new JSObject();
            ret.put("status", "stopped");
            ret.put("message", "Stopped with error: " + e.getMessage());
            call.resolve(ret);
        }
    }

    @PluginMethod
    public void removeAllListeners(PluginCall call) {
        try {
            Log.d(TAG, "removeAllListeners method called");
            notifyListeners("packetCaptured", null);
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error in removeAllListeners", e);
            call.reject("Error removing listeners: " + e.getMessage(), e);
        }
    }

    private void startVpnService(PluginCall call) {
        try {
            Log.d(TAG, "Starting VPN service");
            vpnServiceIntent = new Intent(getActivity(), ToyVpnService.class);
            getActivity().startService(vpnServiceIntent);
            
            JSObject result = new JSObject();
            result.put("status", "started");
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Error in startVpnService", e);
            call.reject("Error starting VPN service: " + e.getMessage(), e);
        }
    }
    
    /**
     * Direct method to stop the VPN service without requiring a PluginCall
     * Used when the app is being destroyed and we need to ensure cleanup
     */
    public void directStopVpn() {
        Log.d(TAG, "EXTREME VPN TERMINATION: directStopVpn called (without plugin call)");
        
        // First immediately notify frontend that VPN is stopping - this will clear UI state
        try {
            notifyListeners("vpnStopping", new JSObject());
        } catch (Exception e) {
            Log.e(TAG, "Error sending vpnStopping event", e);
        }
        
        // Create a thread to handle VPN stopping to avoid blocking UI
        Thread stopThread = new Thread(() -> {
            boolean stoppedSuccessfully = false;
            
            // First immediately try to kill any VPN service threads
            try {
                Log.d(TAG, "EXTREME VPN TERMINATION: Pre-emptively identifying and interrupting VPN threads");
                Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
                for (Thread thread : threadSet) {
                    if (thread.getName().contains("VPN") || 
                        thread.getName().contains("Toy") || 
                        thread.getName().contains("network") || 
                        thread.getName().contains("packet")) {
                        
                        Log.d(TAG, "EXTREME VPN TERMINATION: Interrupting thread: " + thread.getName());
                        thread.interrupt();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error interrupting VPN threads", e);
            }
            
            // Send disconnect action aggressively with multiple approaches
            try {
                // Application context approach
                Context appContext = getContext().getApplicationContext();
                Intent intent = new Intent(appContext, ToyVpnService.class);
                intent.setAction(ToyVpnService.ACTION_DISCONNECT);
                appContext.startService(intent);
                Log.d(TAG, "EXTREME VPN TERMINATION: Sent disconnect action via application context");
                
                // Direct context approach
                intent = new Intent(getContext(), ToyVpnService.class);
                intent.setAction(ToyVpnService.ACTION_DISCONNECT);
                getContext().startService(intent);
                Log.d(TAG, "EXTREME VPN TERMINATION: Sent disconnect action via direct context");
                
                // Activity context approach if available
                if (getActivity() != null) {
                    intent = new Intent(getActivity(), ToyVpnService.class);
                    intent.setAction(ToyVpnService.ACTION_DISCONNECT);
                    getActivity().startService(intent);
                    Log.d(TAG, "EXTREME VPN TERMINATION: Sent disconnect action via activity context");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending disconnect action", e);
            }
            
            // Give the disconnect action a moment to take effect
            try { Thread.sleep(100); } catch (InterruptedException e) { /* ignore */ }
            
            // Immediate stop attempts with multiple approaches
            try {
                // Application context stop
                Context appContext = getContext().getApplicationContext();
                Intent intent = new Intent(appContext, ToyVpnService.class);
                boolean stopped = appContext.stopService(intent);
                Log.d(TAG, "EXTREME VPN TERMINATION: App context stopService result: " + stopped);
                if (stopped) stoppedSuccessfully = true;
                
                // Direct context stop
                intent = new Intent(getContext(), ToyVpnService.class);
                stopped = getContext().stopService(intent);
                Log.d(TAG, "EXTREME VPN TERMINATION: Direct stopService result: " + stopped);
                if (stopped) stoppedSuccessfully = true;
                
                // Activity context stop if available
                if (getActivity() != null) {
                    intent = new Intent(getActivity(), ToyVpnService.class);
                    stopped = getActivity().stopService(intent);
                    Log.d(TAG, "EXTREME VPN TERMINATION: Activity stopService result: " + stopped);
                    if (stopped) stoppedSuccessfully = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping service directly", e);
            }
            
            // Try to directly find and kill the service
            try {
                ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(Integer.MAX_VALUE);
                
                for (ActivityManager.RunningServiceInfo service : services) {
                    if (ToyVpnService.class.getName().equals(service.service.getClassName())) {
                        Log.d(TAG, "EXTREME VPN TERMINATION: Found running VPN service with pid: " + service.pid);
                        // Try to forcefully stop it
                        try {
                            Log.d(TAG, "EXTREME VPN TERMINATION: Attempting to kill process with pid: " + service.pid);
                            android.os.Process.killProcess(service.pid);
                            // Add a small delay to let the system process the kill command
                            Thread.sleep(200);
                        } catch (Exception e) {
                            Log.e(TAG, "Error killing process: " + service.pid, e);
                        }
                        stoppedSuccessfully = true;
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error killing service process", e);
            }
            
            // Reset the vpnServiceIntent to ensure it's not reused
            vpnServiceIntent = null;
            
            // Notify that VPN is stopped
            getBridge().executeOnMainThread(() -> {
                try {
                    notifyListeners("vpnStopped", new JSObject());
                    Log.d(TAG, "EXTREME VPN TERMINATION: Sent vpnStopped event to JS layer from direct method");
                } catch (Exception e) {
                    Log.e(TAG, "Error sending vpnStopped event from direct method", e);
                }
            });
        });
        
        // Set as daemon to avoid blocking app shutdown
        stopThread.setDaemon(true);
        stopThread.start();
    }
    
    /**
     * Static method to notify VPN stopped event from other classes like ToyVpnService
     * This is used because notifyListeners is protected and can't be called directly from outside Plugin classes
     */
    public static void notifyVpnStopped() {
        Log.d(TAG, "Static notifyVpnStopped called");
        if (instance != null) {
            try {
                // Create an empty JSObject for the event data
                JSObject data = new JSObject();
                // Use the instance to call the protected notifyListeners method
                instance.notifyListeners("vpnStopped", data);
                Log.d(TAG, "VPN stopped event sent via static method");
            } catch (Exception e) {
                Log.e(TAG, "Error in static notifyVpnStopped method", e);
            }
        } else {
            Log.e(TAG, "Cannot notify VPN stopped - no plugin instance available");
        }
    }
    
    public class ToyVpnJSInterface {
        @JavascriptInterface
        public String requestVpnPermission() {
            Log.d(TAG, "JS Interface: requestVpnPermission called");
            try {
                Intent intent = VpnService.prepare(getActivity());
                if (intent != null) {
                    Log.d(TAG, "JS Interface: VPN permission required");
                    
                    getActivity().runOnUiThread(() -> {
                        try {
                            getActivity().startActivityForResult(intent, REQUEST_VPN_PERMISSION);
                            Log.d(TAG, "JS Interface: VPN permission intent launched");
                        } catch (Exception e) {
                            Log.e(TAG, "Error starting VPN permission activity", e);
                        }
                    });
                    
                    JSONObject result = new JSONObject();
                    result.put("status", "permission_required");
                    result.put("message", "VPN permission requested");
                    return result.toString();
                } else {
                    Log.d(TAG, "JS Interface: VPN permission already granted");
                    hasVpnPermission = true;
                    JSONObject result = new JSONObject();
                    result.put("status", "permission_granted");
                    return result.toString();
                }
            } catch (Exception e) {
                Log.e(TAG, "JS Interface: Error in requestVpnPermission", e);
                try {
                    JSONObject error = new JSONObject();
                    error.put("status", "error");
                    error.put("message", e.getMessage());
                    return error.toString();
                } catch (JSONException je) {
                    return "{\"status\":\"error\",\"message\":\"Unknown error\"}";
                }
            }
        }
        
        @JavascriptInterface
        public String startVpn(String optionsJson) {
            Log.d(TAG, "JS Interface: startVpn called with options: " + optionsJson);
            try {
                // Check if we need permission first
                Intent intent = VpnService.prepare(getActivity());
                if (intent != null && !hasVpnPermission) {
                    Log.d(TAG, "JS Interface: VPN permission required");
                    
                    getActivity().runOnUiThread(() -> {
                        try {
                            getActivity().startActivityForResult(intent, REQUEST_VPN_PERMISSION);
                            Log.d(TAG, "JS Interface: VPN permission intent launched");
                        } catch (Exception e) {
                            Log.e(TAG, "Error starting VPN permission activity", e);
                        }
                    });
                    
                    JSONObject result = new JSONObject();
                    result.put("status", "permission_required");
                    result.put("message", "VPN permission requested");
                    return result.toString();
                } else {
                    // Start the VPN service
                    vpnServiceIntent = new Intent(getActivity(), ToyVpnService.class);
                    getActivity().startService(vpnServiceIntent);
                    
                    JSONObject result = new JSONObject();
                    result.put("status", "started");
                    return result.toString();
                }
            } catch (Exception e) {
                Log.e(TAG, "JS Interface: Error in startVpn", e);
                try {
                    JSONObject error = new JSONObject();
                    error.put("status", "error");
                    error.put("message", e.getMessage());
                    return error.toString();
                } catch (JSONException je) {
                    return "{\"status\":\"error\",\"message\":\"Unknown error\"}";
                }
            }
        }
        
        @JavascriptInterface
        public String stopVpn() {
            Log.d(TAG, "JS Interface: stopVpn called");
            try {
                // First immediately notify frontend that VPN is stopping
                if (instance != null) {
                    instance.notifyListeners("vpnStopping", new JSObject());
                }
                
                // Use the main directStopVpn method to leverage all the aggressive termination techniques
                if (instance != null) {
                    instance.directStopVpn();
                } else {
                    // Fallback if instance is not available
                    Log.d(TAG, "JS Interface: Instance not available, using fallback stopVpn mechanism");
                    
                    // Try to stop by class as fallback in all contexts
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                        // We're on the main thread
                        if (vpnServiceIntent != null) {
                            getActivity().stopService(vpnServiceIntent);
                            vpnServiceIntent = null;
                        }
                        
                        // Try to stop by class as well
                        Intent stopIntent = new Intent(getActivity(), ToyVpnService.class);
                        stopIntent.setAction(ToyVpnService.ACTION_DISCONNECT);
                        getActivity().startService(stopIntent);
                        getActivity().stopService(stopIntent);
                    } else {
                        // We're on a background thread
                        getActivity().runOnUiThread(() -> {
                            if (vpnServiceIntent != null) {
                                getActivity().stopService(vpnServiceIntent);
                                vpnServiceIntent = null;
                            }
                            
                            // Try to stop by class as well
                            Intent stopIntent = new Intent(getActivity(), ToyVpnService.class);
                            stopIntent.setAction(ToyVpnService.ACTION_DISCONNECT);
                            getActivity().startService(stopIntent);
                            getActivity().stopService(stopIntent);
                        });
                    }
                    
                    // Notify that VPN is stopped even in fallback case
                    if (instance != null) {
                        instance.notifyListeners("vpnStopped", new JSObject());
                    }
                }
                
                JSONObject result = new JSONObject();
                try {
                    result.put("status", "stopping");
                    result.put("message", "VPN stopping process initiated");
                    return result.toString();
                } catch (JSONException je) {
                    return "{\"status\":\"stopping\",\"message\":\"VPN stopping process initiated\"}";
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping VPN in JS interface", e);
                return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
            }
        }
        
        @JavascriptInterface
        public void addListener(String eventName) {
            Log.d(TAG, "JS Interface: addListener called for event: " + eventName);
        }
        
        @JavascriptInterface
        public String removeAllListeners() {
            Log.d(TAG, "JS Interface: removeAllListeners called");
            try {
                JSONObject result = new JSONObject();
                result.put("status", "success");
                return result.toString();
            } catch (JSONException je) {
                return "{\"status\":\"success\"}";
            }
        }
    }
}
