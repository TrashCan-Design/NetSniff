import { WebPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';
import type { ToyVpnPlugin, PacketData } from './ToyVpn';

export class ToyVpnWeb extends WebPlugin implements ToyVpnPlugin {
  async requestVpnPermission(): Promise<{ status: string; message?: string }> {
    console.log('Web: requestVpnPermission not supported');
    return { 
      status: 'not_supported', 
      message: 'VPN operations not available on web' 
    };
  }
  
  async startVpn(options?: any): Promise<{ status: string; message?: string }> {
    console.log('Web: startVpn not supported');
    return { 
      status: 'not_supported',
      message: 'VPN operations not available on web'
    };
  }
  
  async stopVpn(): Promise<{ status: string; message?: string }> {
    console.log('Web: stopVpn not supported');
    return { 
      status: 'not_supported',
      message: 'VPN operations not available on web'
    };
  }
  
  async addListener(
    eventName: 'packetCaptured' | 'vpnStopped' | 'packetBatch',
    listenerFunc: any
  ): Promise<PluginListenerHandle> {
    console.log('Web: addListener called for', eventName);
    return {
      remove: async () => {
        console.log('Web: listener removed');
      }
    };
  }
  
  async removeAllListeners(): Promise<void> {
    console.log('Web: removeAllListeners called');
  }

  // ADDED BY KRINA
    // ==================== DATABASE METHODS (web fallbacks) ====================

  async getTraffic(_options: { limit?: number }): Promise<{ traffic: any[] }> {
    console.warn('ToyVpnWeb: getTraffic() not implemented for web.');
    return { traffic: [] };
  }

  async getBlacklist(): Promise<{ blacklist: any[] }> {
    console.warn('ToyVpnWeb: getBlacklist() not implemented for web.');
    return { blacklist: [] };
  }

  async addToBlacklist(_options: { domain: string; description?: string }): Promise<{ ok: boolean }> {
    console.warn('ToyVpnWeb: addToBlacklist() not implemented for web.');
    return { ok: false };
  }

  async removeFromBlacklist(_options: { domain: string }): Promise<{ ok: boolean }> {
    console.warn('ToyVpnWeb: removeFromBlacklist() not implemented for web.');
    return { ok: false };
  }

  async setBlacklistEnabled(_options: { domain: string; enabled: boolean }): Promise<{ ok: boolean }> {
    console.warn('ToyVpnWeb: setBlacklistEnabled() not implemented for web.');
    return { ok: false };
  }

}