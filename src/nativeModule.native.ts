import { Platform } from 'react-native';
import { NitroModules } from 'react-native-nitro-modules';

import type { NitroNfc } from './NitroNfc.nitro';

const MODULE_NAME = 'NitroNfc';

let hybridObject: NitroNfc | null = null;
let didCreateHybridObject = false;

export function getNitroNfcHybridObject(): NitroNfc | null {
  if (didCreateHybridObject) {
    return hybridObject;
  }

  try {
    hybridObject = NitroModules.createHybridObject<NitroNfc>(MODULE_NAME);
  } catch {
    hybridObject = null;
  }

  didCreateHybridObject = true;

  return hybridObject;
}

export function getLinkingError(packageName: string): string {
  return (
    `'${packageName}' is not linked. ` +
    Platform.select({ ios: "Run 'pod install'. ", default: '' }) +
    'Rebuild your app after installing.'
  );
}
