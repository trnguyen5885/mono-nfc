import 'package:flutter/services.dart';
import 'package:identity_nfc_platform_interface/identity_nfc_platform_interface.dart';

/// Public Flutter API for the NFCSDK native cores.
///
/// The Android and iOS implementations call `nfc-core` and `NFCCore` directly;
/// they never invoke the React Native Nitro adapter.
class IdentityNfc {
  IdentityNfc._();

  static final IdentityNfcPlatform _methodChannelPlatform =
      _MethodChannelIdentityNfc();

  static IdentityNfcPlatform get _platform {
    final platform = IdentityNfcPlatform.instance;
    if (platform.isFallback) {
      IdentityNfcPlatform.instance = _methodChannelPlatform;
      return _methodChannelPlatform;
    }
    return platform;
  }

  static Future<bool> isAvailable() => _platform.isAvailable();

  static Future<IdentityNfcScanResult> scan(IdentityNfcScanRequest request) =>
      _platform.scan(request);

  static Stream<IdentityNfcProgress> get progress => _platform.progress;

  /// Clears the native core's short-lived in-memory DG2 cache.
  static Future<void> clearCachedScan() => _platform.clearCachedScan();
}

class _MethodChannelIdentityNfc extends IdentityNfcPlatform {
  static const MethodChannel _methods = MethodChannel('identity_nfc/methods');
  static const EventChannel _events = EventChannel('identity_nfc/progress');

  @override
  Future<void> clearCachedScan() async {
    await _invoke<void>('clearCachedScan');
  }

  @override
  Future<bool> isAvailable() async {
    return await _invoke<bool>('isAvailable') ?? false;
  }

  @override
  Stream<IdentityNfcProgress> get progress => _events
      .receiveBroadcastStream()
      .map((Object? event) => IdentityNfcProgress.fromMap(
            Map<Object?, Object?>.from(event! as Map),
          ));

  @override
  Future<IdentityNfcScanResult> scan(IdentityNfcScanRequest request) async {
    final result = await _invoke<Map<Object?, Object?>>('scan', request.toMap());
    if (result == null) {
      throw const IdentityNfcException(
        code: 'EmptyResult',
        message: 'The native NFC adapter returned no scan result.',
      );
    }
    return IdentityNfcScanResult.fromMap(result);
  }

  Future<T?> _invoke<T>(String method, [Object? arguments]) async {
    try {
      return await _methods.invokeMethod<T>(method, arguments);
    } on PlatformException catch (error) {
      throw IdentityNfcException(
        code: error.code,
        message: error.message ?? 'The native NFC operation failed.',
      );
    }
  }
}
