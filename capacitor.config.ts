import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'dk.rodgrod.app',
  appName: 'Rødgrød',
  webDir: 'dist',
  android: {
    // Never allow cleartext; all calls go to Azure over HTTPS.
    allowMixedContent: false,
  },
};

export default config;
