import Foundation
import XCTest
@testable import NFCCore

final class NfcCoreContractTests: XCTestCase {
  func testRequestDefaultsAndCachePolicyWireValues() {
    let request = NfcScanRequest(citizenId: "068203011565")

    XCTAssertEqual(request.citizenId, "068203011565")
    XCTAssertTrue(request.readImage)
    XCTAssertEqual(request.cachePolicy, .fresh)
    XCTAssertEqual(request.language, "en")
    XCTAssertEqual(NfcCachePolicy(wireValue: "reuse-if-valid"), .reuseIfValid)
    XCTAssertEqual(NfcCachePolicy(wireValue: "unexpected"), .fresh)
  }

  func testResultExposesBridgeFieldsWithoutChangingRawData() {
    let result = NfcScanResult(
      citizenId: "068203011565",
      fullName: "NGUYEN VAN A",
      dob: "15/08/1995",
      gender: "Male",
      nationality: "Vietnam",
      permanentAddress: "Ha Noi",
      issueDate: "25/12/2021",
      issuePlace: "Bo Cong An",
      expireDate: "15/08/2035",
      imageFromChipData: Data("face".utf8),
      chipImageMimeType: "image/jpeg",
      dg1Data: Data([1, 2]),
      dg2Data: Data([3]),
      dg13Data: Data([4]),
      dg14Data: Data([5]),
      sodData: Data([6])
    )

    XCTAssertEqual(result.imageFromChip, "data:image/jpeg;base64,ZmFjZQ==")
    XCTAssertEqual(result.imageFace, result.imageFromChip)
    XCTAssertEqual(result.liveFaceImage, "")
    XCTAssertEqual(result.frontCardFileId, "")
    XCTAssertEqual(result.backCardFileId, "")
    XCTAssertEqual(result.dg1DataB64, "AQI=")
    XCTAssertEqual(result.dg2DataB64, "Aw==")
    XCTAssertEqual(result.dg13DataB64, "BA==")
    XCTAssertEqual(result.dg14DataB64, "BQ==")
    XCTAssertEqual(result.sodDataB64, "Bg==")
    XCTAssertEqual(result.sodDataBase64, "Bg==")
    XCTAssertFalse(result.passiveAuth)
    XCTAssertFalse(result.chipAuth)
    XCTAssertEqual(result.dg1Data, Data([1, 2]))
  }

  func testEmptyImageDoesNotCreateDataUri() {
    let result = NfcScanResult(
      citizenId: "068203011565",
      fullName: "",
      dob: "",
      gender: "",
      nationality: "",
      permanentAddress: "",
      issueDate: "",
      issuePlace: "",
      expireDate: "",
      imageFromChipData: Data(),
      chipImageMimeType: "",
      dg1Data: Data(),
      dg2Data: Data(),
      dg13Data: Data(),
      dg14Data: Data(),
      sodData: Data()
    )

    XCTAssertEqual(result.imageFromChip, "")
  }

  func testErrorPayloadMatchesCodeAndMessage() {
    let error = NfcCoreError.readFailed(
      code: "PACEError",
      message: "PACE authentication failed"
    )

    XCTAssertEqual(
      error.payload,
      NfcCoreErrorPayload(code: "PACEError", message: "PACE authentication failed")
    )
    XCTAssertEqual(error.payloadDictionary["code"], "PACEError")
    XCTAssertEqual(error.payloadDictionary["message"], "PACE authentication failed")
  }

  func testDg2CacheFingerprintAndClearRemainStable() {
    NfcCore.clearCachedScan()
    let fingerprint = Dg2Cache.fingerprint(dg1: Data([1, 2]), sod: Data([3, 4]))

    XCTAssertNotNil(fingerprint)
    XCTAssertNotEqual(
      fingerprint,
      Dg2Cache.fingerprint(dg1: Data([1, 2, 3]), sod: Data([4]))
    )

    guard let fingerprint else {
      return XCTFail("Expected a fingerprint for non-empty DG1/SOD")
    }
    let cached = CachedDg2(
      dg2Data: Data([9]),
      imageData: Data([8]),
      mimeType: "image/jpeg"
    )
    Dg2Cache.put(fingerprint, value: cached)
    XCTAssertEqual(Dg2Cache.get(fingerprint)?.dg2Data, Data([9]))

    NfcCore.clearCachedScan()
    XCTAssertNil(Dg2Cache.get(fingerprint))
  }

  func testParserAndMrzCharacterization() {
    let dg13 = Data([
      0x6D, 0x1F, // [APPLICATION 13]
      0x30, 0x1D, // SEQUENCE
      0x31, 0x1B, // SET
      0x30, 0x11, 0x02, 0x01, 0x02, 0x0C, 0x0C,
      0x4E, 0x47, 0x55, 0x59, 0x45, 0x4E, 0x20, 0x56, 0x41, 0x4E, 0x20, 0x41,
      0x30, 0x06, 0x02, 0x01, 0x04, 0x0C, 0x01, 0x4D,
    ])

    let parsed = Dg13Parser.parse(dg13, fallbackIssuePlace: "")
    XCTAssertEqual(parsed.fullName, "NGUYEN VAN A")
    XCTAssertEqual(parsed.gender, "Male")
    XCTAssertEqual(Dg13Parser.normalizeGender("nam"), "Male")
    XCTAssertEqual(Dg13Parser.normalizeGender("NU"), "Female")
    XCTAssertEqual(Dg13Parser.normalizeFullName("NGUYEN<<VAN<A"), "NGUYEN VAN A")
    XCTAssertEqual(Dg13Parser.parse(Data(), fallbackIssuePlace: "Bo Cong An").issuePlace, "Bo Cong An")
    XCTAssertEqual(MrzUtils.formatDate("20351231"), "31/12/2035")
    XCTAssertEqual(MrzUtils.normalizeNationality("VNM"), "Vietnam")
    XCTAssertEqual(
      NfcCoreErrorMapper.localized(.userCanceled, language: "vi").payload,
      NfcCoreErrorPayload(code: "UserCanceled", message: "Phiên NFC đã bị hủy")
    )
  }
}
