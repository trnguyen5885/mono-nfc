import { NitroModules } from 'react-native-nitro-modules';
import type { NitroNfc } from './NitroNfc.nitro';

const NitroNfcHybridObject =
  NitroModules.createHybridObject<NitroNfc>('NitroNfc');

export function multiply(a: number, b: number): number {
  return NitroNfcHybridObject.multiply(a, b);
}
