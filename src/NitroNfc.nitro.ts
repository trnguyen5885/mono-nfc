import type { HybridObject } from 'react-native-nitro-modules';

export interface NFCProgressPayload {
  progress: number;
  message: string;
  hasError: boolean;
  errorCode: string;
  errorMessage: string;
}

export interface NitroNfcScanResult {
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
}

export interface NitroNfc extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  isAvailable(): boolean;
  scan(
    citizenId: string,
    onProgress: (event: NFCProgressPayload) => void
  ): Promise<NitroNfcScanResult>;
  getDataGroupBuffer(name: string): ArrayBuffer;
  getDataGroupBase64(name: string): string;
  clearCachedScan(): void;
}
