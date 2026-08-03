import { defineConfig, mergeConfig } from 'vite';

import config from 'react-native-builder-bob/vite-config';
import pack from '../../packages/react-native-nitro-nfc/package.json' with { type: 'json' };

export default defineConfig((env) =>
  mergeConfig(config(env), {
    resolve: {
      alias: {
        [pack.name]: new URL('../../packages/react-native-nitro-nfc', import.meta.url),
      },
      conditions: ['react-native-nitro-nfc-source'],
      dedupe: Object.keys(pack.peerDependencies),
    },
  })
);
