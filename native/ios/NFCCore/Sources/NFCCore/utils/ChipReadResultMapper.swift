import Foundation

#if SWIFT_PACKAGE
import NFCPassportReader
#endif

enum ChipReadResultMapper {
  static func mapResult(
    passport: NFCPassportModel,
    citizenId: String,
    cachedDg2: CachedDg2? = nil
  ) -> ChipReadResult {
    let chipImage = cachedDg2.map {
      (data: $0.imageData, mimeType: $0.mimeType)
    } ?? extractChipImage(from: passport)

    let dg1Data = passport.dataGroupsRead[.DG1].map { Data($0.data) } ?? Data()
    let dg2Data = cachedDg2?.dg2Data
      ?? passport.dataGroupsRead[.DG2].map { Data($0.data) }
      ?? Data()
    let dg13Data = passport.dataGroupsRead[.DG13].map { Data($0.data) } ?? Data()
    let dg14Data = passport.dataGroupsRead[.DG14].map { Data($0.data) } ?? Data()
    let sodData = passport.dataGroupsRead[.SOD].map { Data($0.data) } ?? Data()

    let parsedDg13: DG13ParsedData
    if dg13Data.isEmpty {
      parsedDg13 = DG13ParsedData()
    } else {
      parsedDg13 = DG13Parser.parse(dg13Data, fallbackIssuePlace: "")
    }

    let normalizedGender = DG13Parser.normalizeGender(passport.gender)
    let finalGender =
      parsedDg13.gender.isEmpty ? normalizedGender : parsedDg13.gender
    let mrzExpireDate = MrzUtils.formatDate(passport.documentExpiryDate)
    let finalExpireDate =
      parsedDg13.expireDate.isEmpty ? mrzExpireDate : parsedDg13.expireDate
    let mrzFullName = MrzUtils.normalizeName(
      firstName: passport.firstName,
      lastName: passport.lastName
    )
    let finalFullName =
      parsedDg13.fullName.isEmpty ? mrzFullName : parsedDg13.fullName

    return ChipReadResult(
      citizenId: citizenId,
      fullName: finalFullName,
      dob: MrzUtils.formatDate(passport.dateOfBirth),
      gender: finalGender,
      nationality: MrzUtils.normalizeNationality(passport.nationality),
      permanentAddress: parsedDg13.permanentAddress,
      issueDate: parsedDg13.issueDate,
      issuePlace: parsedDg13.issuePlace,
      expireDate: finalExpireDate,
      imageFromChipData: chipImage.data,
      chipImageMimeType: chipImage.mimeType,
      dg1Data: dg1Data,
      dg2Data: dg2Data,
      dg13Data: dg13Data,
      dg14Data: dg14Data,
      sodData: sodData
    )
  }

  private static func extractChipImage(
    from passport: NFCPassportModel
  ) -> (data: Data, mimeType: String) {
    if let dg2 = passport.dataGroupsRead[.DG2] as? DataGroup2,
       !dg2.imageData.isEmpty {
      // Keep JPEG data in its original encoded form. This avoids decoding and
      // re-encoding a large biometric image after the NFC read has completed.
      if dg2.imageDataType == 0 {
        return (Data(dg2.imageData), "image/jpeg")
      }

      // JPEG2000 is not consistently supported by native image consumers, so
      // convert it only when a decoded UIImage is available. If conversion is
      // unavailable, preserve the original bytes and MIME type for callers
      // that support JPEG2000.
      if dg2.imageDataType == 1,
         let image = passport.passportImage,
         let imageData = image.jpegData(compressionQuality: 0.8) {
        return (imageData, "image/jpeg")
      }

      return (Data(dg2.imageData), "image/jp2")
    }

    // Keep a defensive fallback for reader versions/cards where DG2 exposes a
    // decoded passportImage but not the raw imageData property.
    if let image = passport.passportImage,
       let imageData = image.jpegData(compressionQuality: 0.8) {
      return (imageData, "image/jpeg")
    }

    return (Data(), "")
  }
}
