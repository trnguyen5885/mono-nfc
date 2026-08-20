package com.vppos.nfc.core

import com.vppos.nfc.core.utils.NfcUiText
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceInfo

/** Reads card-access data groups and extracts their encoded payloads. */
internal object NfcDataGroupReader {
  fun readPaceInfo(passportService: PassportService): PACEInfo? =
    CardAccessFile(
      passportService.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE),
    ).securityInfos.filterIsInstance<PACEInfo>().firstOrNull()

  fun readOptional(
    passportService: PassportService,
    fileId: Short,
    progress: Int,
    stage: NfcStage,
    uiText: NfcUiText,
    progressListener: NfcProgressListener,
    cancellationSignal: NfcScanCancellationSignal?,
  ): ByteArray = try {
    cancellationSignal?.throwIfCancelled()
    progressListener.onProgress(progress, uiText.readingData)
    passportService.getInputStream(fileId, PassportService.DEFAULT_MAX_BLOCKSIZE).use { it.readBytes() }
  } catch (error: Exception) {
    cancellationSignal?.throwIfCancelled(error)
    NfcLogger.failure(stage, error)
    ByteArray(0)
  }

  fun extractPortrait(dg2Bytes: ByteArray): ChipImage {
    if (dg2Bytes.isEmpty()) return ChipImage(ByteArray(0), "")
    return try {
      val imageInfo = DG2File(ByteArrayInputStream(dg2Bytes)).getSubRecords().asSequence()
        .filterIsInstance<FaceInfo>().flatMap { it.faceImageInfos.asSequence() }.firstOrNull()
        ?: return ChipImage(ByteArray(0), "")
      if (imageInfo.imageLength <= 0) return ChipImage(ByteArray(0), "")
      val bytes = DataInputStream(imageInfo.imageInputStream).use { input ->
        ByteArray(imageInfo.imageLength).also(input::readFully)
      }
      ChipImage(bytes, imageInfo.mimeType.orEmpty())
    } catch (error: Exception) {
      NfcLogger.failure(NfcStage.EXTRACT_DG2_IMAGE, error)
      ChipImage(ByteArray(0), "")
    }
  }
}
