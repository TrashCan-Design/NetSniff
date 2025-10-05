import React, { createContext, useContext, useState, useEffect, ReactNode, useMemo } from 'react';
import { ToyVpn, PacketData } from '../plugins/ToyVpn';

export interface Packet extends PacketData {
  id: string;
  timestamp: number;
  appName?: string;
  packageName?: string;
}

export interface SearchFilters {
  sourceIp?: string;
  destinationIp?: string;
  direction?: 'incoming' | 'outgoing' | 'all';
  protocol?: string;
  appName?: string;
  payloadSearch?: string;
}

interface PacketContextProps {
  packets: Packet[];
  filteredPackets: Packet[];
  isCapturing: boolean;
  isConnecting: boolean;
  hasVpnPermission: boolean;
  searchFilters: SearchFilters;
  setSearchFilters: (filters: SearchFilters) => void;
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
    appDistribution: { [key: string]: number };
  };
  error: string | null;
}

const PacketContext = createContext<PacketContextProps | undefined>(undefined);

export const PacketProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [packets, setPackets] = useState<Packet[]>([]);
  const [isCapturing, setIsCapturing] = useState(false);
  const [isConnecting, setIsConnecting] = useState(false);
  const [hasVpnPermission, setHasVpnPermission] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchFilters, setSearchFilters] = useState<SearchFilters>({
    direction: 'all'
  });

  // Filtered packets based on search criteria
  const filteredPackets = useMemo(() => {
    return packets.filter(packet => {
      // Direction filter
      if (searchFilters.direction && searchFilters.direction !== 'all') {
        if (packet.direction !== searchFilters.direction) return false;
      }

      // Source IP filter
      if (searchFilters.sourceIp) {
        const searchIp = searchFilters.sourceIp.toLowerCase();
        if (!packet.source.toLowerCase().includes(searchIp)) return false;
      }

      // Destination IP filter
      if (searchFilters.destinationIp) {
        const searchIp = searchFilters.destinationIp.toLowerCase();
        if (!packet.destination.toLowerCase().includes(searchIp)) return false;
      }

      // Protocol filter
      if (searchFilters.protocol) {
        const searchProto = searchFilters.protocol.toLowerCase();
        if (!packet.protocol.toLowerCase().includes(searchProto)) return false;
      }

      // App name filter
      if (searchFilters.appName && packet.appName) {
        const searchApp = searchFilters.appName.toLowerCase();
        if (!packet.appName.toLowerCase().includes(searchApp) && 
            !(packet.packageName || '').toLowerCase().includes(searchApp)) return false;
      }

      // Payload search
      if (searchFilters.payloadSearch) {
        const searchPayload = searchFilters.payloadSearch.toLowerCase();
        if (!packet.payload.toLowerCase().includes(searchPayload)) return false;
      }

      return true;
    });
  }, [packets, searchFilters]);

  // Stats calculation
  const stats = useMemo(() => {
    return {
      totalPackets: packets.length,
      incomingPackets: packets.filter(p => p.direction === 'incoming').length,
      outgoingPackets: packets.filter(p => p.direction === 'outgoing').length,
      totalBytes: packets.reduce((acc, p) => acc + p.size, 0),
      protocolDistribution: packets.reduce((acc, p) => {
        acc[p.protocol] = (acc[p.protocol] || 0) + 1;
        return acc;
      }, {} as { [key: string]: number }),
      appDistribution: packets.reduce((acc, p) => {
        if (p.appName) {
          acc[p.appName] = (acc[p.appName] || 0) + 1;
        }
        return acc;
      }, {} as { [key: string]: number })
    };
  }, [packets]);

  useEffect(() => {
    const setupListener = async () => {
      try {
        console.log("PacketContext: Setting up listeners");
        
        await ToyVpn.removeAllListeners();
        
        const vpnStoppedListener = await ToyVpn.addListener('vpnStopped', () => {
          console.log("PacketContext: VPN stopped event received");
          setIsCapturing(false);
          setIsConnecting(false);
        });
        
        const packetListener = await ToyVpn.addListener('packetCaptured', (data: PacketData) => {
          const packet: Packet = {
            ...data,
            id: `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
            timestamp: Date.now(),
          };
          
          setPackets(prevPackets => {
            const newPackets = [packet, ...prevPackets];
            // Keep last 5000 packets to prevent memory issues
            if (newPackets.length > 5000) {
              return newPackets.slice(0, 5000);
            }
            return newPackets;
          });
        });
        
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

    setupListener();

    return () => {
      ToyVpn.removeAllListeners().catch((error: unknown) => {
        console.error('Failed to remove listeners:', error);
      });
    };
  }, []);

  const requestVpnPermission = async (): Promise<void> => {
    console.log("PacketContext: Requesting VPN permission");
    setIsConnecting(true);
    setError(null);
    
    try {
      const result = await Promise.race([
        ToyVpn.requestVpnPermission(),
        new Promise<any>((_, reject) => 
          setTimeout(() => reject(new Error("VPN permission request timed out")), 15000)
        )
      ]);
      
      console.log("PacketContext: VPN permission result:", result);
      
      if (result.status === 'permission_granted') {
        setHasVpnPermission(true);
      } else {
        throw new Error(`Failed to get VPN permission: ${result?.message || 'Unknown error'}`);
      }
    } catch (error: unknown) {
      console.error('Failed to request VPN permission:', error);
      setError(error instanceof Error ? error.message : 'Failed to request VPN permission');
      throw error;
    } finally {
      setIsConnecting(false);
    }
  };

  const startCapture = async () => {
    console.log("PacketContext: Starting VPN");
    setIsConnecting(true);
    setError(null);
    
    try {
      if (isCapturing) {
        console.log("PacketContext: Already capturing");
        return;
      }
      
      const result = await Promise.race([
        ToyVpn.startVpn({}),
        new Promise<any>((_, reject) => 
          setTimeout(() => reject(new Error("VPN start timed out")), 15000)
        )
      ]);
      
      console.log("PacketContext: VPN start result:", result);
      
      if (result.status === 'started') {
        setIsCapturing(true);
      } else if (result.status === 'permission_required') {
        setHasVpnPermission(false);
        throw new Error("VPN permission required - Please request permission first");
      } else {
        throw new Error(`Failed to start VPN: ${result?.message || 'Unknown error'}`);
      }
    } catch (error: unknown) {
      console.error('Failed to start VPN:', error);
      setError(error instanceof Error ? error.message : 'Failed to start VPN');
      throw error;
    } finally {
      setIsConnecting(false);
    }
  };

  const stopCapture = async () => {
    console.log("PacketContext: Stopping VPN gracefully");
    setIsConnecting(true);
    setError(null);
    
    try {
      if (!isCapturing) {
        console.log("PacketContext: VPN not running");
        return;
      }
      
      setIsCapturing(false);
      
      const result = await Promise.race([
        ToyVpn.stopVpn(),
        new Promise<any>((_, reject) => 
          setTimeout(() => reject(new Error("VPN stop timed out")), 5000)
        )
      ]);
      
      console.log("PacketContext: VPN stop result:", result);
      
    } catch (error: unknown) {
      console.error('Failed to stop VPN:', error);
      setError(error instanceof Error ? error.message : 'Failed to stop VPN');
    } finally {
      setIsConnecting(false);
    }
  };

  const clearPackets = () => {
    setPackets([]);
    setError(null);
  };

  // Handle app state changes
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.hidden && isCapturing) {
        console.log("App going to background, VPN will continue running");
        // VPN continues in background as a foreground service
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [isCapturing]);

  return (
    <PacketContext.Provider value={{
      packets,
      filteredPackets,
      isCapturing,
      isConnecting,
      hasVpnPermission,
      searchFilters,
      setSearchFilters,
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

export const usePackets = () => {
  const context = useContext(PacketContext);
  if (context === undefined) {
    throw new Error('usePackets must be used within a PacketProvider');
  }
  return context;
};