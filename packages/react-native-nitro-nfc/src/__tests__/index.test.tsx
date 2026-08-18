import { beforeEach, describe, expect, it, jest } from '@jest/globals';

const mockScan = jest.fn();

jest.mock('../nativeModule', () => ({
  getLinkingError: () => 'not linked',
  getNitroNfcHybridObject: () => ({
    clearCachedScan: jest.fn(),
    getDataGroupBase64: jest.fn(),
    getDataGroupBuffer: jest.fn(() => new ArrayBuffer(0)),
    isAvailable: jest.fn(() => true),
    scan: mockScan,
  }),
}));

import { NFCSDK } from '../index';

const nativeResult = {
  citizenId: '001234567890',
  fullName: '',
  dob: '',
  gender: '',
  nationality: '',
  permanentAddress: '',
  issueDate: '',
  issuePlace: '',
  expireDate: '',
  chipImageUri: '',
  chipImageMimeType: '',
  imageFromChipSize: 0,
  dg1Size: 0,
  dg2Size: 0,
  dg13Size: 0,
  dg14Size: 0,
  sodSize: 0,
};

describe('NFCSDK progress contract', () => {
  beforeEach(() => {
    mockScan.mockReset();
  });

  it('derives a stable phase from native progress checkpoints', async () => {
    mockScan.mockImplementation((...args: unknown[]) => {
      const onProgress = args[4] as (event: Record<string, unknown>) => void;
      onProgress({
        progress: 20,
        message: 'Authenticating the chip...',
        hasError: false,
        errorCode: '',
        errorMessage: '',
      });
      return Promise.resolve(nativeResult);
    });
    const onProgress = jest.fn();

    await NFCSDK.scan({ citizenId: '001234567890', onProgress });

    expect(onProgress).toHaveBeenCalledWith({
      progress: 20,
      message: 'Authenticating the chip...',
      phase: 'authenticating',
    });
  });

  it('marks NFC-disabled events as recoverable and actionable', async () => {
    mockScan.mockImplementation((...args: unknown[]) => {
      const onProgress = args[4] as (event: Record<string, unknown>) => void;
      onProgress({
        progress: -1,
        message: 'Please enable NFC',
        hasError: true,
        errorCode: 'NFCDisabled',
        errorMessage: 'Please enable NFC',
      });
      return Promise.resolve(nativeResult);
    });
    const onProgress = jest.fn();

    await NFCSDK.scan({ citizenId: '001234567890', onProgress });

    expect(onProgress).toHaveBeenCalledWith({
      error: {
        code: 'NFCDisabled',
        message: 'Please enable NFC',
        recoverable: true,
        suggestedAction: 'open-nfc-settings',
      },
    });
  });

  it('does not duplicate a native error for startScan listeners', async () => {
    mockScan.mockImplementation((...args: unknown[]) => {
      const onProgress = args[4] as (event: Record<string, unknown>) => void;
      onProgress({
        progress: -1,
        message: 'NFC session was canceled',
        hasError: true,
        errorCode: 'UserCanceled',
        errorMessage: 'NFC session was canceled',
      });
      return Promise.reject(
        new Error('UserCanceled: NFC session was canceled')
      );
    });
    const listener = jest.fn();
    const subscription = NFCSDK.onProgress(listener);

    NFCSDK.startScan({ citizenId: '001234567890' });
    await Promise.resolve();
    await Promise.resolve();

    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({
      error: {
        code: 'UserCanceled',
        message: 'NFC session was canceled',
        recoverable: false,
        suggestedAction: 'close',
      },
    });
    subscription.remove();
  });
});
