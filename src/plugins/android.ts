import { registerPlugin, PluginListenerHandle } from '@capacitor/core';
import type { ToyVpnPlugin, PacketData } from './ToyVpn';

export class ToyVpnPluginAndroid implements ToyVpnPlugin {
    constructor() {
        console.log('ToyVpnPluginAndroid constructor initialized');
    }

    async requestVpnPermission(): Promise<{ status: string; message?: string }> {
        console.log('ToyVpnPluginAndroid.requestVpnPermission called');
        const capacitorWindow = window as any;
        return capacitorWindow.Capacitor.Plugins.ToyVpn.requestVpnPermission();
    }

    async startVpn(options?: { 
        serverAddress?: string; 
        serverPort?: string; 
        sharedSecret?: string;
    }): Promise<{ status: string; message?: string }> {
        console.log('ToyVpnPluginAndroid.startVpn called with options:', options);
        // This will call the native implementation through Capacitor's bridge
        const capacitorWindow = window as any;
        return capacitorWindow.Capacitor.Plugins.ToyVpn.startVpn(options);
    }

    async stopVpn(): Promise<{ status: string }> {
        console.log('ToyVpnPluginAndroid.stopVpn called');
        // This will call the native implementation
        const capacitorWindow = window as any;
        return capacitorWindow.Capacitor.Plugins.ToyVpn.stopVpn();
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
        console.log('ToyVpnPluginAndroid.addListener called for event:', eventName);
        const capacitorWindow = window as any;
        return capacitorWindow.Capacitor.Plugins.ToyVpn.addListener(eventName, listenerFunc);
    }

    async removeAllListeners(): Promise<void> {
        console.log('ToyVpnPluginAndroid.removeAllListeners called');
        const capacitorWindow = window as any;
        return capacitorWindow.Capacitor.Plugins.ToyVpn.removeAllListeners();
    }
}

// This creates and exports the Android implementation of the ToyVpn plugin
export const ToyVpnAndroid = new ToyVpnPluginAndroid();

registerPlugin('ToyVpn', {
  web: () => import('./web').then(m => m.ToyVpnPluginWeb),
  android: () => Promise.resolve(ToyVpnAndroid)
});
