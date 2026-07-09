export class NFCSDKError extends Error {
  readonly code: string;

  constructor(message: string, code = 'Unknown') {
    super(message);
    this.name = 'NFCSDKError';
    this.code = code;
  }
}
