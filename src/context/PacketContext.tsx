import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { ToyVpn, PacketData } from '../plugins';

// Define the packet type
export interface Packet extends PacketData {
  id: string;
  timestamp: number;
}

// Define the context shape
interface PacketContextProps {
  packets: Packet[];
  isCapturing: boolean;
  isConnecting: boolean;
  hasVpnPermission: boolean;
  requestVpnPermission: () => Promise<void>;
  startCapture: () => Promise<void>;
  stopCapture: () => Promise<void>;
  clearPackets: () => void;
  stats: {
    totalPackets: number;
    incomingPackets: number;
    outgoingPackets: number;
    totalBytes: number;
    protocolDistribution: { [key: string]: number };
  };
  error: string | null;
}

// Create the context
const PacketContext = createContext<PacketContextProps | undefined>(undefined);

// Create a provider component
export const PacketProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [packets, setPackets] = useState<Packet[]>([]);
  const [isCapturing, setIsCapturing] = useState(false);
  const [isConnecting, setIsConnecting] = useState(false);
  const [hasVpnPermission, setHasVpnPermission] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Stats calculation
  const stats = React.useMemo(() => {
    return {
      totalPackets: packets.length,
      incomingPackets: packets.filter(p => p.direction === 'incoming').length,
      outgoingPackets: packets.filter(p => p.direction === 'outgoing').length,
      totalBytes: packets.reduce((acc, p) => acc + p.size, 0),
      protocolDistribution: packets.reduce((acc, p) => {
        acc[p.protocol] = (acc[p.protocol] || 0) + 1;
        return acc;
      }, {} as { [key: string]: number })
    };
  }, [packets]);

  useEffect(() => {
    // Set up packet capture listener
    const setupListener = async () => {
      try {
        console.log("PacketContext: Setting up packet capture listener");
        
        // Clear any old listeners first
        await ToyVpn.removeAllListeners();
        
        // Listen for the vpnStopped event
        const vpnStoppedListener = await ToyVpn.addListener('vpnStopped', () => {
          console.log("PacketContext: VPN stopped event received");
          setIsCapturing(false);
        });
        
        // Add listener for packet capture events
        const packetListener = await ToyVpn.addListener('packetCaptured', (data: PacketData) => {
          if (!isCapturing) {
            console.log("PacketContext: Received packet while not capturing - VPN may still be running");
            setIsCapturing(true);
          }
          
          const packet: Packet = {
            ...data,
            id: Math.random().toString(36).substring(7),
            timestamp: Date.now(),
          };
          
          setPackets(prevPackets => {
            // Keep only last 1000 packets to prevent memory issues
            const newPackets = [packet, ...prevPackets];
            if (newPackets.length > 1000) {
              return newPackets.slice(0, 1000);
            }
            return newPackets;
          });
        });
        
        // Store the listener handles for cleanup
        return () => {
          vpnStoppedListener.remove();
          packetListener.remove();
        };
      } catch (error: unknown) {
        console.error('Failed to setup packet listener:', error);
        setError(error instanceof Error ? error.message : 'Failed to setup packet capture');
        return null;
      }
    };

    // Call setup
    setupListener();

    // Check if VPN is already running when app is restarted
    const checkExistingVpnStatus = async () => {
      try {
        // We can detect if packets are still being received which indicates the VPN is running
        setTimeout(() => {
          if (packets.length > 0 && !isCapturing) {
            console.log("PacketContext: Detected packets are being received, VPN appears to be running");
            setIsCapturing(true);
          }
        }, 1000);
      } catch (error) {
        console.error("Failed to check VPN status:", error);
      }
    };
    
    checkExistingVpnStatus();

    return () => {
      // Cleanup listener on component unmount
      ToyVpn.removeAllListeners().catch((error: unknown) => {
        console.error('Failed to remove listeners:', error);
      });
    };
  }, []);  // Note: intentionally not including packets or isCapturing to avoid re-registering listeners

  // Request VPN permission - separate from starting the capture
  const requestVpnPermission = async (): Promise<void> => {
    console.log("PacketContext: Requesting VPN permission");
    setIsConnecting(true);
    setError(null);
    
    try {
      // Check if the ToyVpn plugin is available
      if (!ToyVpn) {
        throw new Error("ToyVpn plugin not available");
      }
      
      const result = await Promise.race([
        ToyVpn.requestVpnPermission(),
        new Promise<any>((_, reject) => 
          setTimeout(() => reject(new Error("VPN permission request timed out")), 10000)
        )
      ]);
      
      console.log("PacketContext: VPN permission request result:", result);
      
      if (result.status === 'permission_granted') {
        setHasVpnPermission(true);
      } else {
        throw new Error(`Failed to get VPN permission: ${result?.message || 'Unknown error'}`);
      }
    } catch (error: unknown) {
      console.error('Failed to request VPN permission:', error);
      setError(error instanceof Error ? error.message : 'Failed to request VPN permission');
    } finally {
      setIsConnecting(false);
    }
  };

  // Start packet capture - should only be called after permission is granted
  const startCapture = async () => {
    console.log("PacketContext: Attempting to start VPN");
    setIsConnecting(true);
    setError(null);
    
    try {
      if (isCapturing) {
        console.log("PacketContext: VPN capture already running");
        return;
      }
      
      // Check if the ToyVpn plugin is available
      if (!ToyVpn) {
        throw new Error("ToyVpn plugin not available");
      }
      
      console.log("PacketContext: Native ToyVpn plugin detected");
      
      // Start the VPN service
      const result = await Promise.race([
        ToyVpn.startVpn({}),
        new Promise<any>((_, reject) => 
          setTimeout(() => reject(new Error("VPN start timed out")), 10000)
        )
      ]);
      
      console.log("PacketContext: VPN start result:", result);
      
      if (result.status === 'started') {
        setIsCapturing(true);
      } else if (result.status === 'permission_required') {
        // This should not happen if the flow is followed correctly
        // but we'll handle it for robustness
        setHasVpnPermission(false);
        throw new Error("VPN permission required - Please request permission first");
      } else {
        throw new Error(`Failed to start VPN: ${result?.message || 'Unknown error'}`);
      }
    } catch (error: unknown) {
      console.error('Failed to start VPN:', error);
      setError(error instanceof Error ? error.message : 'Failed to start VPN');
    } finally {
      setIsConnecting(false);
    }
  };

  // Stop packet capture - gentle approach
  const stopCapture = async () => {
    console.log("PacketContext: Attempting to stop VPN with gentle approach");
    setIsConnecting(true);
    setError(null);
    
    try {
      if (!isCapturing) {
        console.log("PacketContext: VPN not running, nothing to stop");
        return;
      }
      
      console.log("PacketContext: Current state before stopping - isCapturing:", isCapturing, "isConnecting:", isConnecting);
      
      // Update UI state to indicate stopping process has begun
      setIsCapturing(false);
      
      // Single attempt with reasonable timeout
      try {
        console.log("PacketContext: Making a single, gentle stop attempt");
        const result = await Promise.race([
          ToyVpn.stopVpn(),
          new Promise<any>((_, reject) => 
            setTimeout(() => reject(new Error("VPN stop timed out after 5000ms")), 5000)
          )
        ]);
        
        console.log("PacketContext: Stop attempt result:", result);
        
        if (result?.status === "stopped") {
          console.log("PacketContext: VPN successfully stopped");
        }
      } catch (err) {
        console.error("PacketContext: Error in stop attempt:", err);
        // Don't throw here, just log the error
      }
      
      // Clear packets
      console.log("PacketContext: Clearing packets from state");
      setPackets([]);
      
      // Ensure UI state is updated
      setIsCapturing(false);
      
    } catch (error: unknown) {
      console.error('PacketContext: Failure in stop VPN process:', error);
      setError(error instanceof Error ? error.message : 'Failed to stop VPN');
      
      // Always ensure UI state is updated even in case of errors
      setIsCapturing(false);
    } finally {
      setIsConnecting(false);
      console.log("PacketContext: Stop capture operation completed");
    }
  };

  const clearPackets = () => {
    setPackets([]);
    setError(null);
  };

  useEffect(() => {
    const handleAppStateChange = async (state: { isActive: boolean }) => {
      // Handle app going to background or being terminated when VPN is active
      if (!state.isActive && isCapturing) {
        console.log("App going to background or being terminated while VPN is active. Stopping VPN...");
        try {
          await ToyVpn.stopVpn();
          setIsCapturing(false);
        } catch (error) {
          console.error("Failed to stop VPN on app state change:", error);
        }
      }
    };

    // Set up app state change listener - using optional chaining for safety
    const app = (window as any)?.Capacitor?.Plugins?.App;
    
    let appStateListener: any = null;
    let backButtonListener: any = null;
    
    if (app && typeof app.addListener === 'function') {
      // Listen for app state changes (background/foreground)
      appStateListener = app.addListener(
        'appStateChange',
        handleAppStateChange
      );
      
      // Also listen for back button events which might exit the app
      backButtonListener = app.addListener(
        'backButton',
        async () => {
          if (isCapturing) {
            console.log("Back button pressed, possibly exiting. Stopping VPN to be safe.");
            try {
              await ToyVpn.stopVpn();
              setIsCapturing(false);
            } catch (error) {
              console.error("Failed to stop VPN on back button:", error);
            }
          }
        }
      );
    }
    
    // Setup window beforeunload event for web and hybrid contexts
    const handleBeforeUnload = async () => {
      if (isCapturing) {
        console.log("Application is being unloaded. Stopping VPN...");
        try {
          await ToyVpn.stopVpn();
        } catch (error) {
          console.error("Failed to stop VPN on app unload:", error);
        }
      }
    };
    
    window.addEventListener('beforeunload', handleBeforeUnload);
    
    // Return cleanup function
    return () => {
      if (appStateListener && typeof appStateListener.remove === 'function') {
        appStateListener.remove();
      }
      
      if (backButtonListener && typeof backButtonListener.remove === 'function') {
        backButtonListener.remove();
      }
      
      window.removeEventListener('beforeunload', handleBeforeUnload);
      
      // Also ensure VPN is stopped when component unmounts
      if (isCapturing) {
        console.log("Component unmounting while VPN is active. Stopping VPN...");
        ToyVpn.stopVpn().catch(error => {
          console.error("Failed to stop VPN on component unmount:", error);
        });
      }
    };
  }, [isCapturing]);

  return (
    <PacketContext.Provider value={{
      packets,
      isCapturing,
      isConnecting,
      hasVpnPermission,
      requestVpnPermission,
      startCapture,
      stopCapture,
      clearPackets,
      stats,
      error
    }}>
      {children}
    </PacketContext.Provider>
  );
};

// Custom hook to use the packet context
export const usePackets = () => {
  const context = useContext(PacketContext);
  if (context === undefined) {
    throw new Error('usePackets must be used within a PacketProvider');
  }
  return context;
};
