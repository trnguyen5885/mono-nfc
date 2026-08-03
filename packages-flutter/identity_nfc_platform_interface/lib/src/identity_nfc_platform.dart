import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'identity_nfc_models.dart';

/// Contract implemented by the Flutter adapter. It mirrors the public native
/// core contract and must not become a replacement model for the native core.
abstract class IdentityNfcPlatform extends PlatformInterface {
  IdentityNfcPlatform() : super(token: _token);

  static final Object _token = Object();
  static IdentityNfcPlatform _instance = _UnsupportedIdentityNfcPlatform();

  static IdentityNfcPlatform get instance => _instance;

  static set instance(IdentityNfcPlatform platform) {
    PlatformInterface.verifyToken(platform, _token);
    _instance = platform;
  }

  /// Identifies the temporary implementation used before the package facade
  /// installs its channel implementation or a test supplies its own adapter.
  bool get isFallback => false;

  Future<bool> isAvailable();
  Future<IdentityNfcScanResult> scan(IdentityNfcScanRequest request);
  Stream<IdentityNfcProgress> get progress;
  Future<void> clearCachedScan();
}

class _UnsupportedIdentityNfcPlatform extends IdentityNfcPlatform {
  @override
  bool get isFallback => true;

  Never _unsupported() => throw UnsupportedError(
        'No IdentityNfc platform implementation has been installed.',
      );

  @override
  Future<void> clearCachedScan() async => _unsupported();

  @override
  Future<bool> isAvailable() async => _unsupported();

  @override
  Stream<IdentityNfcProgress> get progress =>
      Stream<IdentityNfcProgress>.error(_unsupported());

  @override
  Future<IdentityNfcScanResult> scan(IdentityNfcScanRequest request) async =>
      _unsupported();
}
