import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

export interface PacketData {
  packetNumber: number;
  source: string;
  destination: string;
  protocol: string;
  direction: 'incoming' | 'outgoing';
  size: number;
  payload: string;
  appName?: string;
  packageName?: string;
  uid?: number;
}

export interface PacketBatchData {
  packets: PacketData[];
  count: number;
  remaining: number;
}

export interface ToyVpnPlugin {
  requestVpnPermission(): Promise<{ status: string; message?: string }>;
  
  startVpn(options?: { 
    serverAddress?: string; 
    serverPort?: string; 
    sharedSecret?: string;
  }): Promise<{ status: string; message?: string }>;
  
  stopVpn(): Promise<{ status: string; message?: string }>;
  
  // Single packet event (for backwards compatibility)
  addListener(
    eventName: 'packetCaptured',
    listenerFunc: (packet: PacketData) => void
  ): Promise<PluginListenerHandle>;
  
  // Batch packet event (PRIMARY - for performance)
  addListener(
    eventName: 'packetBatch',
    listenerFunc: (batch: PacketBatchData) => void
  ): Promise<PluginListenerHandle>;
  
  // VPN stopped event
  addListener(
    eventName: 'vpnStopped',
    listenerFunc: () => void
  ): Promise<PluginListenerHandle>;
  
  removeAllListeners(): Promise<void>;
}

const ToyVpn = registerPlugin<ToyVpnPlugin>('ToyVpn', {
  web: () => import('./web').then(m => new m.ToyVpnWeb()),
});

export { ToyVpn };