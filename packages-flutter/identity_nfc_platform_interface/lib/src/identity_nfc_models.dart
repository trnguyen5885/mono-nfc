import 'dart:typed_data';

/// Controls whether a valid, short-lived portrait cache can be reused.
enum IdentityNfcCachePolicy {
  fresh('fresh'),
  reuseIfValid('reuse-if-valid');

  const IdentityNfcCachePolicy(this.wireValue);

  final String wireValue;
}

/// Input accepted by both NFCSDK native cores.
class IdentityNfcScanRequest {
  const IdentityNfcScanRequest({
    required this.citizenId,
    this.readImage = true,
    this.cachePolicy = IdentityNfcCachePolicy.fresh,
    this.language = 'en',
  });

  final String citizenId;
  final bool readImage;
  final IdentityNfcCachePolicy cachePolicy;
  final String language;

  Map<String, Object> toMap() => <String, Object>{
        'citizenId': citizenId,
        'readImage': readImage,
        'cachePolicy': cachePolicy.wireValue,
        'language': language,
      };
}

/// A progress update emitted while a physical NFC scan is active.
class IdentityNfcProgress {
  const IdentityNfcProgress({
    required this.progress,
    required this.message,
    this.hasError = false,
    this.errorCode,
    this.errorMessage,
  });

  factory IdentityNfcProgress.fromMap(Map<Object?, Object?> map) {
    return IdentityNfcProgress(
      progress: (map['progress'] as num?)?.toDouble() ?? 0,
      message: map['message'] as String? ?? '',
      hasError: map['hasError'] as bool? ?? false,
      errorCode: map['errorCode'] as String?,
      errorMessage: map['errorMessage'] as String?,
    );
  }

  final double progress;
  final String message;
  final bool hasError;
  final String? errorCode;
  final String? errorMessage;
}

/// Data returned by the native NFC core. Binary values cross the platform
/// channel as [Uint8List], never as base64 strings.
class IdentityNfcScanResult {
  const IdentityNfcScanResult({
    required this.citizenId,
    required this.fullName,
    required this.dob,
    required this.gender,
    required this.nationality,
    required this.permanentAddress,
    required this.issueDate,
    required this.issuePlace,
    required this.expireDate,
    required this.imageFromChipData,
    required this.chipImageMimeType,
    required this.dg1Data,
    required this.dg2Data,
    required this.dg13Data,
    required this.dg14Data,
    required this.sodData,
  });

  factory IdentityNfcScanResult.fromMap(Map<Object?, Object?> map) {
    return IdentityNfcScanResult(
      citizenId: map['citizenId'] as String? ?? '',
      fullName: map['fullName'] as String? ?? '',
      dob: map['dob'] as String? ?? '',
      gender: map['gender'] as String? ?? '',
      nationality: map['nationality'] as String? ?? '',
      permanentAddress: map['permanentAddress'] as String? ?? '',
      issueDate: map['issueDate'] as String? ?? '',
      issuePlace: map['issuePlace'] as String? ?? '',
      expireDate: map['expireDate'] as String? ?? '',
      imageFromChipData: _bytes(map['imageFromChipData']),
      chipImageMimeType: map['chipImageMimeType'] as String? ?? '',
      dg1Data: _bytes(map['dg1Data']),
      dg2Data: _bytes(map['dg2Data']),
      dg13Data: _bytes(map['dg13Data']),
      dg14Data: _bytes(map['dg14Data']),
      sodData: _bytes(map['sodData']),
    );
  }

  final String citizenId;
  final String fullName;
  final String dob;
  final String gender;
  final String nationality;
  final String permanentAddress;
  final String issueDate;
  final String issuePlace;
  final String expireDate;
  final Uint8List imageFromChipData;
  final String chipImageMimeType;
  final Uint8List dg1Data;
  final Uint8List dg2Data;
  final Uint8List dg13Data;
  final Uint8List dg14Data;
  final Uint8List sodData;

  static Uint8List _bytes(Object? value) {
    if (value is Uint8List) return value;
    if (value is ByteData) return value.buffer.asUint8List();
    if (value is List<int>) return Uint8List.fromList(value);
    return Uint8List(0);
  }
}

/// A stable, framework-level error containing no sensitive document data.
class IdentityNfcException implements Exception {
  const IdentityNfcException({required this.code, required this.message});

  final String code;
  final String message;

  @override
  String toString() => 'IdentityNfcException($code): $message';
}
