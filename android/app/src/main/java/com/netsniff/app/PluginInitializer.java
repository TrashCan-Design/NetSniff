package com.netsniff.app;

import android.content.Context;
import android.util.Log;
import com.getcapacitor.Bridge;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginHandle;
import java.util.HashMap;
import java.util.Map;

public class PluginInitializer {
    private static final String TAG = "PluginInitializer";

    /**
     * Method to force-initialize our custom plugin when the app starts
     */
    public static void initializePlugins(Bridge bridge) {
        Log.d(TAG, "Manually initializing plugins");
        
        try {
            // Create instance of our plugin
            ToyVpnPlugin toyVpnPlugin = new ToyVpnPlugin();
            
            // Manually register the plugin with the bridge
            Map<String, PluginHandle> pluginMap = getPluginsMap(bridge);
            if (pluginMap != null) {
                String pluginName = "ToyVpn";
                
                // Create a plugin handle
                PluginHandle handle = new PluginHandle(bridge, toyVpnPlugin);
                
                // Add it to the plugins map
                pluginMap.put(pluginName, handle);
                
                // Initialize the plugin
                toyVpnPlugin.load();
                
                Log.d(TAG, "Successfully registered ToyVpn plugin manually");
            } else {
                Log.e(TAG, "Could not access plugins map");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing plugins", e);
        }
    }
    
    /**
     * Get access to the Bridge's plugins map using reflection
     */
    @SuppressWarnings({"unchecked", "JavaReflectionMemberAccess"})
    private static Map<String, PluginHandle> getPluginsMap(Bridge bridge) {
        try {
            java.lang.reflect.Field pluginsField = Bridge.class.getDeclaredField("plugins");
            pluginsField.setAccessible(true);
            return (Map<String, PluginHandle>) pluginsField.get(bridge);
        } catch (Exception e) {
            Log.e(TAG, "Error accessing plugins field", e);
            return null;
        }
    }
}
