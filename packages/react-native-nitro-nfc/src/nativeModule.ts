import type { NitroNfc } from './NitroNfc.nitro';

export function getNitroNfcHybridObject(): NitroNfc | null {
  return null;
}

export function getLinkingError(packageName: string): string {
  return `'${packageName}' is not available on this platform.`;
}
