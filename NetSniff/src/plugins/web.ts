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
    eventName: 'packetCaptured' | 'vpnStopped',
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
}