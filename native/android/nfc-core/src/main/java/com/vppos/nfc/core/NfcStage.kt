package com.vppos.nfc.core

/** Stable lifecycle stages for scan progress, logging and future analytics. */
internal enum class NfcStage {
  VALIDATE,
  INITIALIZE_CRYPTO,
  GET_ISODEP,
  CONFIGURE_ISODEP,
  OPEN_CARD_SERVICE,
  CREATE_PASSPORT_SERVICE,
  OPEN_PASSPORT_SERVICE,
  READ_CARD_ACCESS,
  PACE_AUTHENTICATION,
  SELECT_APPLET,
  READ_DG1,
  READ_DG13,
  READ_DG14,
  READ_SOD,
  REUSE_CACHED_DG2,
  READ_DG2,
  EXTRACT_DG2_IMAGE,
}
