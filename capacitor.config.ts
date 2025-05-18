import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.netsniff.app',
  appName: 'NetSniff',
  webDir: 'dist',
  plugins: {
    ToyVpn: {
      serverAddress: '127.0.0.1',
      serverPort: '8000',
      sharedSecret: 'shared_secret'
    }
  },
  android: {
    buildOptions: {
      keystorePath: undefined,
      keystorePassword: undefined,
      keystoreAlias: undefined,
      keystoreAliasPassword: undefined,
      signingType: 'apksigner'
    }
  }
};

export default config;
