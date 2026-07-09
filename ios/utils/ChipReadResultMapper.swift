import Foundation

enum ChipReadResultMapper {
  static func mapResult(
    passport: NFCPassportModel,
    citizenId: String
  ) -> ChipReadResult {
    var imageFromChipData = Data()
    var chipImageMimeType = ""
    if let image = passport.passportImage,
       let imageData = image.jpegData(compressionQuality: 0.8) {
      imageFromChipData = imageData
      chipImageMimeType = "image/jpeg"
    }

    let dg1Data = passport.dataGroupsRead[.DG1].map { Data($0.data) } ?? Data()
    let dg2Data = passport.dataGroupsRead[.DG2].map { Data($0.data) } ?? Data()
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
      imageFromChipData: imageFromChipData,
      chipImageMimeType: chipImageMimeType,
      dg1Data: dg1Data,
      dg2Data: dg2Data,
      dg13Data: dg13Data,
      dg14Data: dg14Data,
      sodData: sodData
    )
  }
}
