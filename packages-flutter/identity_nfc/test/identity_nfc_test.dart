import 'package:flutter_test/flutter_test.dart';
import 'package:identity_nfc/identity_nfc.dart';

void main() {
  test('serializes the public scan request contract', () {
    const request = IdentityNfcScanRequest(
      citizenId: '012345678901',
      readImage: false,
      cachePolicy: IdentityNfcCachePolicy.reuseIfValid,
      language: 'vi',
    );

    expect(request.toMap(), <String, Object>{
      'citizenId': '012345678901',
      'readImage': false,
      'cachePolicy': 'reuse-if-valid',
      'language': 'vi',
    });
  });
}
