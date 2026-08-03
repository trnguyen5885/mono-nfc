import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:identity_nfc_platform_interface/identity_nfc_platform_interface.dart';

void main() {
  test('maps native binary values without base64 conversion', () {
    final result = IdentityNfcScanResult.fromMap(<Object?, Object?>{
      'citizenId': '012345678901',
      'imageFromChipData': Uint8List.fromList(<int>[1, 2]),
      'dg1Data': Uint8List.fromList(<int>[3]),
    });

    expect(result.imageFromChipData, Uint8List.fromList(<int>[1, 2]));
    expect(result.dg1Data, Uint8List.fromList(<int>[3]));
    expect(result.dg2Data, isEmpty);
  });
}
