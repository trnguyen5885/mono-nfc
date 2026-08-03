/**
 * react-native-nitro-nfc — Read citizen ID data through NFC using Nitro Modules.
 *
 * The default scan result contains lightweight metadata only. Large binary data
 * such as the chip image and DG1/DG2/DG13/DG14/SOD are loaded lazily through
 * `getDataGroupBuffer` or `getDataGroupBase64` to reduce JavaScript thread load.
 *
 * @packageDocumentation
 * @module react-native-nitro-nfc
 */

import { NFCSDKError } from './errors';
import { getLinkingError, getNitroNfcHybridObject } from './nativeModule';
import type {
  NFCDataGroupName,
  NFCErrorEvent,
  NFCLanguage,
  NFCProgressEvent,
  NFCScanCachePolicy,
  NFCScanResult,
  NFCSubscription,
} from './types';

const PACKAGE_NAME = 'react-native-nitro-nfc';

const progressListeners = new Set<(event: NFCProgressEvent) => void>();
const resultListeners = new Set<(result: NFCScanResult) => void>();
let currentScan: Promise<NFCScanResult> | null = null;

type NativeProgressPayload = {
  progress: number;
  message: string;
  hasError: boolean;
  errorCode: string;
  errorMessage: string;
};

type NativeScanResult = {
  citizenId: string;
  fullName: string;
  dob: string;
  gender: string;
  nationality: string;
  permanentAddress: string;
  issueDate: string;
  issuePlace: string;
  expireDate: string;
  chipImageUri: string;
  chipImageMimeType: string;
  imageFromChipSize: number;
  dg1Size: number;
  dg2Size: number;
  dg13Size: number;
  dg14Size: number;
  sodSize: number;
};

function requireModule() {
  const nativeModule = getNitroNfcHybridObject();

  if (!nativeModule) {
    throw new NFCSDKError(getLinkingError(PACKAGE_NAME));
  }

  return nativeModule;
}

function validateCitizenId(citizenId: string): string {
  const id = String(citizenId ?? '').trim();

  if (!id || id.length < 6 || !/^\d+$/.test(id)) {
    throw new NFCSDKError('Invalid citizen ID', 'InvalidCitizenId');
  }

  return id;
}

function notifyProgress(event: NFCProgressEvent): void {
  progressListeners.forEach((listener) => listener(event));
}

function notifyResult(result: NFCScanResult): void {
  resultListeners.forEach((listener) => listener(result));
}

function createSubscription<T>(
  listeners: Set<(value: T) => void>,
  listener: (value: T) => void
): NFCSubscription {
  listeners.add(listener);

  return {
    remove() {
      listeners.delete(listener);
    },
  };
}

function normalizeProgress(event: NativeProgressPayload): NFCProgressEvent {
  if (event.hasError) {
    return {
      error: {
        code: event.errorCode || 'Unknown',
        message: event.errorMessage || event.message || 'NFC read failed',
      },
    };
  }

  return {
    progress: Math.round(Number(event.progress ?? 0)),
    message: String(event.message ?? ''),
  };
}

function normalizeResult(result: NativeScanResult): NFCScanResult {
  return {
    citizenId: result.citizenId || undefined,
    fullName: result.fullName || undefined,
    dob: result.dob || undefined,
    gender: result.gender || undefined,
    nationality: result.nationality || undefined,
    permanentAddress: result.permanentAddress || undefined,
    issueDate: result.issueDate || undefined,
    issuePlace: result.issuePlace || undefined,
    expireDate: result.expireDate || undefined,
    chipImageUri: result.chipImageUri || undefined,
    chipImageMimeType: result.chipImageMimeType || undefined,
    imageFromChipSize: Math.max(
      0,
      Math.round(Number(result.imageFromChipSize))
    ),
    dg1Size: Math.max(0, Math.round(Number(result.dg1Size))),
    dg2Size: Math.max(0, Math.round(Number(result.dg2Size))),
    dg13Size: Math.max(0, Math.round(Number(result.dg13Size))),
    dg14Size: Math.max(0, Math.round(Number(result.dg14Size))),
    sodSize: Math.max(0, Math.round(Number(result.sodSize))),
  };
}

function normalizeError(caughtError: unknown): NFCErrorEvent {
  if (caughtError instanceof NFCSDKError) {
    return {
      code: caughtError.code,
      message: caughtError.message,
    };
  }

  if (caughtError instanceof Error) {
    return {
      code: 'Unknown',
      message: caughtError.message,
    };
  }

  return {
    code: 'Unknown',
    message: 'NFC read failed',
  };
}

function normalizeDataGroupName(name: NFCDataGroupName): string {
  return name.toUpperCase();
}

export { NFCSDKError } from './errors';
export type {
  NFCDataGroupName,
  NFCErrorEvent,
  NFCProgressEvent,
  NFCScanCachePolicy,
  NFCLanguage,
  NFCScanResult,
  NFCSubscription,
} from './types';

export const NFCSDK = {
  isAvailable(): boolean {
    const nativeModule = getNitroNfcHybridObject();

    if (!nativeModule || typeof nativeModule.isAvailable !== 'function') {
      return false;
    }

    try {
      return nativeModule.isAvailable();
    } catch {
      return false;
    }
  },

  async scan(options: {
    citizenId: string;
    /** Read DG2 and extract the chip image. Defaults to true. */
    readImage?: boolean;
    /** Reuse DG2 only when the current DG1/SOD fingerprint matches. */
    cachePolicy?: NFCScanCachePolicy;
    /** Language used by the native NFC UI and user-facing messages. Defaults to English. */
    language?: NFCLanguage;
    onProgress?: (event: NFCProgressEvent) => void;
  }): Promise<NFCScanResult> {
    const id = validateCitizenId(options.citizenId);
    const nativeModule = requireModule();

    const nativeResult = await nativeModule.scan(
      id,
      options.readImage !== false,
      options.cachePolicy === 'reuse-if-valid' ? 'reuse-if-valid' : 'fresh',
      options.language === 'vi' ? 'vi' : 'en',
      (event) => {
        const progressEvent = normalizeProgress(event);
        options.onProgress?.(progressEvent);
        notifyProgress(progressEvent);
      }
    );
    const result = normalizeResult(nativeResult);
    notifyResult(result);

    return result;
  },

  startScan(options: {
    citizenId: string;
    readImage?: boolean;
    cachePolicy?: NFCScanCachePolicy;
    language?: NFCLanguage;
  }): void {
    if (currentScan) {
      return;
    }

    const scanPromise = NFCSDK.scan(options);
    currentScan = scanPromise;

    scanPromise
      .catch((caughtError) => {
        notifyProgress({ error: normalizeError(caughtError) });
      })
      .finally(() => {
        if (currentScan === scanPromise) {
          currentScan = null;
        }
      });
  },

  onScanResult(listener: (result: NFCScanResult) => void): NFCSubscription {
    return createSubscription(resultListeners, listener);
  },

  onProgress(listener: (event: NFCProgressEvent) => void): NFCSubscription {
    return createSubscription(progressListeners, listener);
  },

  getDataGroupBuffer(name: NFCDataGroupName): ArrayBuffer | undefined {
    const buffer = requireModule().getDataGroupBuffer(
      normalizeDataGroupName(name)
    );

    return buffer.byteLength > 0 ? buffer : undefined;
  },

  getDataGroupBase64(name: NFCDataGroupName): string | undefined {
    const base64 = requireModule().getDataGroupBase64(
      normalizeDataGroupName(name)
    );

    return base64 || undefined;
  },

  clearCachedScan(): void {
    requireModule().clearCachedScan();
  },
};

export default NFCSDK;
