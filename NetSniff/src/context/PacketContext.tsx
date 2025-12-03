import React, { createContext, useContext, useState, useEffect, ReactNode, useMemo, useCallback, useRef } from 'react';
import { ToyVpn, PacketData } from '../plugins/ToyVpn';

export interface Packet extends PacketData {
  id: string;
  timestamp: number;
  appName?: string;
  packageName?: string;
}

export interface SearchFilters {
  searchQuery?: string; // Unified search for all fields
  direction?: 'incoming' | 'outgoing' | 'all';
}

interface PacketBatchData {
  packets: PacketData[];
  count: number;
  remaining: number;
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
  loadMorePackets: () => void;
  hasMorePackets: boolean;
  loadOldPackets: () => Promise<void>;
}

const PacketContext = createContext<PacketContextProps | undefined>(undefined);

const PACKETS_PER_PAGE = 100;
const MAX_PACKETS_IN_MEMORY = 10000; // Increased from 5000

export const PacketProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [allPackets, setAllPackets] = useState<Packet[]>([]);
  const [displayedPacketsCount, setDisplayedPacketsCount] = useState(PACKETS_PER_PAGE);
  const [isCapturing, setIsCapturing] = useState(false);
  const [isConnecting, setIsConnecting] = useState(false);
  const [hasVpnPermission, setHasVpnPermission] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchFilters, setSearchFilters] = useState<SearchFilters>({
    direction: 'all',
    searchQuery: ''
  });

  // Use ref to track if app is in background
  const isBackgroundRef = useRef(false);
  const pendingPacketsRef = useRef<Packet[]>([]);
  const batchProcessingRef = useRef(false);

  // Displayed packets (paginated)
  const packets = useMemo(() => {
    return allPackets.slice(0, displayedPacketsCount);
  }, [allPackets, displayedPacketsCount]);

  // Unified search filter - searches across all fields
  const filteredPackets = useMemo(() => {
    return packets.filter(packet => {
      // Direction filter
      if (searchFilters.direction && searchFilters.direction !== 'all') {
        if (packet.direction !== searchFilters.direction) return false;
      }

      // Unified search query - searches source, destination, protocol, app, payload
      if (searchFilters.searchQuery) {
        const query = searchFilters.searchQuery.toLowerCase();
        const searchableText = [
          packet.source,
          packet.destination,
          packet.protocol,
          packet.appName || '',
          packet.packageName || '',
          packet.payload,
          packet.packetNumber.toString()
        ].join(' ').toLowerCase();
        
        if (!searchableText.includes(query)) return false;
      }

      return true;
    });
  }, [packets, searchFilters]);

  // Stats calculation
  const stats = useMemo(() => {
    return {
      totalPackets: allPackets.length,
      incomingPackets: allPackets.filter(p => p.direction === 'incoming').length,
      outgoingPackets: allPackets.filter(p => p.direction === 'outgoing').length,
      totalBytes: allPackets.reduce((acc, p) => acc + p.size, 0),
      protocolDistribution: allPackets.reduce((acc, p) => {
        acc[p.protocol] = (acc[p.protocol] || 0) + 1;
        return acc;
      }, {} as { [key: string]: number }),
      appDistribution: allPackets.reduce((acc, p) => {
        if (p.appName) {
          acc[p.appName] = (acc[p.appName] || 0) + 1;
        }
        return acc;
      }, {} as { [key: string]: number })
    };
  }, [allPackets]);

  const hasMorePackets = displayedPacketsCount < allPackets.length;

  const loadMorePackets = useCallback(() => {
    setDisplayedPacketsCount(prev => 
      Math.min(prev + PACKETS_PER_PAGE, allPackets.length)
    );
  }, [allPackets.length]);

  // Batch process pending packets (when app comes to foreground)
  const processPendingPackets = useCallback(() => {
    if (batchProcessingRef.current || pendingPacketsRef.current.length === 0) return;
    
    batchProcessingRef.current = true;
    console.log(`Processing ${pendingPacketsRef.current.length} pending packets`);
    
    setAllPackets(prevPackets => {
      const newPackets = [...pendingPacketsRef.current, ...prevPackets];
      pendingPacketsRef.current = [];
      
      // Limit total packets
      if (newPackets.length > MAX_PACKETS_IN_MEMORY) {
        return newPackets.slice(0, MAX_PACKETS_IN_MEMORY);
      }
      return newPackets;
    });
    
    batchProcessingRef.current = false;
  }, []);

  // Add packet (optimized for background/foreground)
  const addPackets = useCallback((newPackets: Packet[]) => {
    if (isBackgroundRef.current) {
      // Queue packets when in background
      pendingPacketsRef.current.push(...newPackets);
      console.log(`Queued ${newPackets.length} packets (total queued: ${pendingPacketsRef.current.length})`);
    } else {
      // Add immediately when in foreground
      setAllPackets(prevPackets => {
        const combined = [...newPackets, ...prevPackets];
        if (combined.length > MAX_PACKETS_IN_MEMORY) {
          return combined.slice(0, MAX_PACKETS_IN_MEMORY);
        }
        return combined;
      });
    }
  }, []);
  /*Added By Krina*/
  /*Added By Krina*/
  const loadOldPackets = async () => {
    try {
      const old = await ToyVpn.getSavedTraffic();  // Native plugin call
      console.log("getSavedTraffic() result:", old);

      if (old && Array.isArray(old.traffic)) {
        // Map old packets to current Packet structure
        const mapped = old.traffic.map((r: any, i: number) => ({
          id: `db-${r.id ?? `${Date.now()}-${i}`}`,
          timestamp: typeof r.timestamp === "number"
            ? r.timestamp
            : Date.parse(r.timestamp) || Date.now(),
          source: r.source_ip && r.source_port
            ? `${r.source_ip}:${r.source_port}`
            : (r.source_ip ?? "unknown"),
          destination: r.dest_ip && r.dest_port
            ? `${r.dest_ip}:${r.dest_port}`
            : (r.dest_ip ?? "unknown"),
          protocol: (r.protocol ?? "").toString().toUpperCase(),
          direction: r.direction === "incoming" ? "incoming" : "outgoing",
          size: Number(r.size) || 0,
          payload: r.payload ?? "",
          appName: r.app_name || undefined,
          packageName: r.package_name || undefined,
          packetNumber: Number(r.id) || i + 1,
        }));

        // Set loaded packets
        setAllPackets(mapped);
        console.log(`loaded ${mapped.length} old packets from DB.`);
      } else {
        console.warn("No old packets found in database.");
        setAllPackets([]); // clear if none
      }
    } catch (error) {
      console.error("❌ Error loading old packets:", error);
      setAllPackets([]); // clear on error
    }
  };


  useEffect(() => {
    const setupListener = async () => {
      try {
        console.log("PacketContext: Setting up listeners");
        
        await ToyVpn.removeAllListeners();
        
        // Listen for VPN stopped event
        const vpnStoppedListener = await ToyVpn.addListener('vpnStopped', () => {
          console.log("PacketContext: VPN stopped event received");
          setIsCapturing(false);
          setIsConnecting(false);
        });
        
        // FIXED: Listen for BATCH events instead of single packet events
        const packetBatchListener = await ToyVpn.addListener('packetBatch', (data: PacketBatchData) => {
          console.log(`Received packet batch: ${data.count} packets, ${data.remaining} remaining`);
          
          const newPackets: Packet[] = data.packets.map(packetData => ({
            ...packetData,
            id: `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
            timestamp: Date.now(),
          }));
          
          addPackets(newPackets);
        });
        
        // Also keep single packet listener for backwards compatibility
        const singlePacketListener = await ToyVpn.addListener('packetCaptured', (data: PacketData) => {
          console.log("Received single packet");
          const packet: Packet = {
            ...data,
            id: `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
            timestamp: Date.now(),
          };
          addPackets([packet]);
        });
        
        return () => {
          vpnStoppedListener.remove();
          packetBatchListener.remove();
          singlePacketListener.remove();
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
  }, [addPackets]);

  // Handle visibility changes (background/foreground)
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.hidden) {
        console.log("App going to background");
        isBackgroundRef.current = true;
      } else {
        console.log("App coming to foreground");
        isBackgroundRef.current = false;
        
        // Process any pending packets
        if (pendingPacketsRef.current.length > 0) {
          setTimeout(() => {
            processPendingPackets();
          }, 100);
        }
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [processPendingPackets]);

  
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



  const clearPackets = useCallback(() => {
    setAllPackets([]);
    pendingPacketsRef.current = [];
    setDisplayedPacketsCount(PACKETS_PER_PAGE);
    setError(null);
  }, []);

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
      error,
      loadMorePackets,
      hasMorePackets,
      loadOldPackets,
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