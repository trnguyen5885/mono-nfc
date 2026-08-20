export type NFCDataGroupName =
  'IMAGE' | 'DG1' | 'DG2' | 'DG13' | 'DG14' | 'SOD';

export type NFCScanCachePolicy = 'fresh' | 'reuse-if-valid';

/** Language used by the native NFC progress screen and messages. */
export type NFCLanguage = 'en' | 'vi';

/** A user-visible phase in the native NFC scanning flow. */
export type NFCProgressPhase =
  | 'opening'
  | 'waiting-for-tag'
  | 'connecting'
  | 'authenticating'
  | 'reading'
  | 'success';

/** Error returned by the native NFC SDK. */
export type NFCErrorEvent = {
  /** Stable error code returned by native code. */
  code: string;
  /** Error message suitable for displaying to the user. */
  message: string;
  /** Whether the native screen remains open and offers the user another attempt. */
  recoverable?: boolean;
  /** Suggested native action for an application that mirrors this status outside the sheet. */
  suggestedAction?: 'retry' | 'open-nfc-settings' | 'close';
};

/** NFC scan progress event. */
export type NFCProgressEvent =
  | {
      /** Completion percentage (0-100). */
      progress: number;
      /** Description of the current step. */
      message: string;
      /** Derived from the stable Android/iOS progress checkpoints. */
      phase?: NFCProgressPhase;
      /** A normal progress event does not contain a native error. */
      error?: undefined;
    }
  | {
      /** Native error when the scan fails or the user needs to retry. */
      error: NFCErrorEvent;
      /** An error event does not contain progress. */
      progress?: undefined;
      /** An error event does not contain a top-level message. */
      message?: undefined;
    };

export type NFCSubscription = {
  remove(): void;
};

/** Lightweight metadata returned after reading a citizen ID through NFC. */
export type NFCScanResult = {
  /** Citizen ID number. */
  citizenId?: string;
  /** Full name. */
  fullName?: string;
  /** Date of birth (DD/MM/YYYY). */
  dob?: string;
  /** Gender (Male/Female/Other). */
  gender?: string;
  /** Nationality. */
  nationality?: string;
  /** Permanent address. */
  permanentAddress?: string;
  /** Issue date (DD/MM/YYYY). */
  issueDate?: string;
  /** Issuing authority/place. */
  issuePlace?: string;
  /** Expiration date (DD/MM/YYYY). */
  expireDate?: string;
  /** Cache file URI for the chip image, when written by native code. */
  chipImageUri?: string;
  /** Suggested MIME type for the chip image. */
  chipImageMimeType?: string;
  /** Chip image size in bytes. */
  imageFromChipSize: number;
  /** DG1 size in bytes. */
  dg1Size: number;
  /** DG2 size in bytes. */
  dg2Size: number;
  /** DG13 size in bytes. */
  dg13Size: number;
  /** DG14 size in bytes. */
  dg14Size: number;
  /** SOD size in bytes. */
  sodSize: number;
};
