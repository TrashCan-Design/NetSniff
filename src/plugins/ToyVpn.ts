import type { PluginListenerHandle } from '@capacitor/core';

export interface PacketData {
  source: string;
  destination: string;
  protocol: string;
  direction: 'incoming' | 'outgoing';
  size: number;
  payload: string;
}

// Define the interface for our ToyVpn plugin
export interface ToyVpnPlugin {
  requestVpnPermission(): Promise<{ status: string; message?: string }>;
  startVpn(options?: { 
    serverAddress?: string; 
    serverPort?: string; 
    sharedSecret?: string;
  }): Promise<{ status: string; message?: string }>;
  stopVpn(): Promise<{ status: string; message?: string }>;
  addListener(
    eventName: 'packetCaptured',
    listenerFunc: (packet: PacketData) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'vpnStopped',
    listenerFunc: () => void
  ): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}

// Define a mock implementation for development/testing
const mockToyVpn: ToyVpnPlugin = {
  requestVpnPermission: async () => {
    console.log('[MOCK] Requesting VPN permission');
    return { status: 'permission_granted' };
  },
  startVpn: async (options?: any) => {
    console.log('[MOCK] Starting VPN with options:', options);
    return { status: 'started' };
  },
  stopVpn: async () => {
    console.log('[MOCK] Stopping VPN');
    return { status: 'stopped' };
  },
  addListener: (eventName: 'packetCaptured' | 'vpnStopped', callback: any): Promise<PluginListenerHandle> => {
    console.log('[MOCK] Adding listener for:', eventName);
    // Return a promise that resolves to a listener handle
    return Promise.resolve({
      remove: () => {
        console.log('[MOCK] Removed listener for:', eventName);
        return Promise.resolve();
      }
    });
  },
  removeAllListeners: async () => {
    console.log('[MOCK] Removing all listeners');
    return Promise.resolve();
  }
};

// Try to get the native implementation if available, otherwise use mock
const ToyVpn = (() => {
  let isNativeImplementation = false;
  let nativePlugin: any;

  try {
    // Check if we're running in a native environment
    if (typeof window !== 'undefined') {
      // Debug logging to help diagnose plugin availability
      console.log('Checking for ToyVpn plugin availability...');
      
      // First check for direct ToyVpnNative interface with the isAvailable flag
      if ((window as any).ToyVpnNative && (window as any).ToyVpnNative.isAvailable) {
        console.log('Direct ToyVpnNative interface found - using real implementation');
        nativePlugin = (window as any).Capacitor.Plugins.ToyVpn;
        isNativeImplementation = true;
      }
      
      // Check Capacitor.Plugins
      const capacitorGlobal = (window as any).Capacitor;
      if (capacitorGlobal && capacitorGlobal.Plugins && capacitorGlobal.Plugins.ToyVpn) {
        console.log('Native ToyVpn plugin found via Capacitor.Plugins');
        nativePlugin = capacitorGlobal.Plugins.ToyVpn;
        isNativeImplementation = true;
      } 
      
      // Check older Capacitor API
      const pluginsGlobal = (window as any).Plugins;
      if (pluginsGlobal && pluginsGlobal.ToyVpn) {
        console.log('Native ToyVpn plugin found via window.Plugins');
        nativePlugin = pluginsGlobal.ToyVpn;
        isNativeImplementation = true;
      }

      // Last resort - check window.ToyVpn which might be set by direct injection
      if ((window as any).ToyVpn) {
        console.log('Native ToyVpn plugin found directly on window object');
        nativePlugin = (window as any).ToyVpn;
        isNativeImplementation = true;
      }
      
      // If we reach here, no native plugin was found
      if (!isNativeImplementation) {
        console.warn('Native ToyVpn plugin not found, using mock implementation');
        
        // Let's create a global fallback to help debugging
        (window as any).ToyVpnDebugInfo = {
          checked: {
            ToyVpnNative: !!(window as any).ToyVpnNative,
            CapacitorPlugins: !!(capacitorGlobal && capacitorGlobal.Plugins),
            CapacitorToyVpn: !!(capacitorGlobal && capacitorGlobal.Plugins && capacitorGlobal.Plugins.ToyVpn),
            Plugins: !!(pluginsGlobal),
            PluginsToyVpn: !!(pluginsGlobal && pluginsGlobal.ToyVpn),
            WindowToyVpn: !!(window as any).ToyVpn
          },
          mockUsed: true
        };
      }
    }
    
    if (!isNativeImplementation) {
      return mockToyVpn;
    }
  } catch (err) {
    console.error('Error accessing ToyVpn plugin:', err);
    return mockToyVpn;
  }

  const toyVpnPlugin: ToyVpnPlugin = {
    requestVpnPermission: async () => {
      if (isNativeImplementation) {
        try {
          console.log("Calling native requestVpnPermission");
          // Wrap the native call in a Promise since it doesn't return one
          return new Promise((resolve) => {
            // Call the native method
            const result = nativePlugin.requestVpnPermission();
            // Return the result as a Promise
            resolve(result || { status: 'permission_granted' });
          });
        } catch (error) {
          console.error('Error calling native requestVpnPermission:', error);
          return Promise.reject(error);
        }
      }
      
      // Mock implementation
      return Promise.resolve({ status: 'permission_granted' });
    },
    startVpn: async (options?: any) => {
      if (isNativeImplementation) {
        try {
          console.log("Calling native startVpn");
          // Wrap the native call in a Promise since it doesn't return one
          return new Promise((resolve) => {
            // Call the native method
            const result = nativePlugin.startVpn(options);
            // Return the result as a Promise
            resolve(result || { status: 'started' });
          });
        } catch (error) {
          console.error('Error calling native startVpn:', error);
          return Promise.reject(error);
        }
      }
      
      // Mock implementation
      return Promise.resolve({ status: 'started' });
    },
    stopVpn: async () => {
      if (isNativeImplementation) {
        try {
          console.log("Calling native stopVpn");
          // Wrap the native call in a Promise since it doesn't return one
          return new Promise((resolve) => {
            // Call the native method
            const result = nativePlugin.stopVpn();
            // Return the result as a Promise
            resolve(result || { status: 'stopped' });
          });
        } catch (error) {
          console.error('Error calling native stopVpn:', error);
          return Promise.reject(error);
        }
      }
      
      // Mock implementation
      return Promise.resolve({ status: 'stopped' });
    },
    addListener: (eventName: 'packetCaptured' | 'vpnStopped', callback: any): Promise<PluginListenerHandle> => {
      if (isNativeImplementation) {
        try {
          console.log("Calling native addListener");
          // Wrap the native call in a Promise since it doesn't return one
          return new Promise((resolve) => {
            // Call the native method
            const result = nativePlugin.addListener(eventName, callback);
            // Return the result as a Promise
            resolve(result);
          });
        } catch (error) {
          console.error('Error calling native addListener:', error);
          return Promise.reject(error);
        }
      }
      
      // Mock implementation
      console.log('[MOCK] Adding listener for:', eventName);
      // Return a promise that resolves to a listener handle
      return Promise.resolve({
        remove: () => {
          console.log('[MOCK] Removed listener for:', eventName);
          return Promise.resolve();
        }
      });
    },
    removeAllListeners: async () => {
      if (isNativeImplementation) {
        try {
          console.log("Calling native removeAllListeners");
          // Wrap in Promise
          return new Promise((resolve) => {
            // Call the native method
            nativePlugin.removeAllListeners();
            resolve();
          });
        } catch (error) {
          console.error('Error calling native removeAllListeners:', error);
          return Promise.reject(error);
        }
      }
      
      // No listeners in mock implementation
      return Promise.resolve();
    }
  };

  return toyVpnPlugin;
})();

// Export the plugin instance
export { ToyVpn };
