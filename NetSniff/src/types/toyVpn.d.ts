/// <reference types="@capacitor/core" />

import type { PluginListenerHandle } from '@capacitor/core';

export interface ToyVpnPlugin {
  /**
   * Start the VPN service
   * @param options Configuration options for the VPN
   * @returns A promise that resolves when the VPN service has started
   */
  startVpn(options?: {
    serverAddress?: string;
    serverPort?: string;
    sharedSecret?: string;
  }): Promise<{ status: string }>;

  /**
   * Stop the VPN service
   * @returns A promise that resolves when the VPN service has stopped
   */
  stopVpn(): Promise<{ status: string }>;

  /**
   * Listen for packet capture events
   * @param eventName The name of the event to listen for
   * @param callback The callback to be called when the event is fired
   * @returns A handle that can be used to remove the listener
   */
  addListener(
    eventName: string,
    callback: (...args: any[]) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Remove all listeners for this plugin
   */
  removeAllListeners(): Promise<void>;
}

declare module '@capacitor/core' {
  interface PluginRegistry {
    ToyVpn: ToyVpnPlugin;
  }
}
