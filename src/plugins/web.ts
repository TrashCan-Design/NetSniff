import { WebPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';
import type { ToyVpnPlugin, PacketData } from './ToyVpn';

class ToyVpnPluginWebImpl extends WebPlugin implements ToyVpnPlugin {
  constructor() {
    super();
  }
  
  async requestVpnPermission(): Promise<{ status: string; message?: string }> {
    // Web implementation of requestVpnPermission
    
    // On web, we don't have VPN permissions concept
    return { status: 'not_supported', message: 'VPN permissions not applicable on web' };
  }
  
  async startVpn(options?: { 
    serverAddress?: string; 
    serverPort?: string; 
    sharedSecret?: string;
  }): Promise<{ status: string; message?: string }> {
    // Web implementation of startVpn
    
    // On web, we can't capture real packets due to browser security restrictions
    // Instead, we'll just return a status indicating this is not supported
    return { status: 'not_supported' };
  }
  
  async stopVpn(): Promise<{ status: string }> {
    // Web implementation of stopVpn
    
    // On web, there's nothing to stop since we don't capture packets
    return { status: 'not_running' };
  }
  
  async addListener(
    eventName: 'packetCaptured',
    listenerFunc: (packet: PacketData) => void
  ): Promise<PluginListenerHandle>;
  async addListener(
    eventName: 'vpnStopped',
    listenerFunc: () => void
  ): Promise<PluginListenerHandle>;
  async addListener(
    eventName: 'packetCaptured' | 'vpnStopped',
    listenerFunc: ((packet: PacketData) => void) | (() => void)
  ): Promise<PluginListenerHandle> {
    // Adding listener for packet capture or vpn stopped event
    
    // On web, we can't capture packets or run VPN, so we'll just return a no-op listener
    return {
      remove: () => Promise.resolve()
    };
  }
  
  async removeAllListeners(): Promise<void> {
    // Removing all listeners
    return Promise.resolve();
  }
}

// Create a singleton instance of the web implementation
const webPlugin = new ToyVpnPluginWebImpl();

export const ToyVpnPluginWeb = webPlugin;
