package com.identity.nfc.identitynfc

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.identity.nfc.core.NfcCachePolicy
import com.identity.nfc.core.NfcCore
import com.identity.nfc.core.NfcCoreException
import com.identity.nfc.core.NfcScanRequest
import com.identity.nfc.core.NfcScanResult
import com.identity.nfc.core.utils.NfcCoreErrorMapper
import com.identity.nfc.core.utils.NfcCoreErrorPayload
import com.identity.nfc.core.utils.NfcUiText

/** Native Android scan UI shared in spirit with the React Native integration. */
internal class IdentityNfcScanActivity : AppCompatActivity() {
  companion object {
    const val EXTRA_CITIZEN_ID = "identity_nfc.citizen_id"
    const val EXTRA_READ_IMAGE = "identity_nfc.read_image"
    const val EXTRA_CACHE_POLICY = "identity_nfc.cache_policy"
    const val EXTRA_LANGUAGE = "identity_nfc.language"
    const val EXTRA_ERROR_CODE = "identity_nfc.error_code"
    const val EXTRA_ERROR_MESSAGE = "identity_nfc.error_message"

    const val RESULT_CITIZEN_ID = "identity_nfc.result.citizen_id"
    const val RESULT_FULL_NAME = "identity_nfc.result.full_name"
    const val RESULT_DOB = "identity_nfc.result.dob"
    const val RESULT_GENDER = "identity_nfc.result.gender"
    const val RESULT_NATIONALITY = "identity_nfc.result.nationality"
    const val RESULT_PERMANENT_ADDRESS = "identity_nfc.result.permanent_address"
    const val RESULT_ISSUE_DATE = "identity_nfc.result.issue_date"
    const val RESULT_ISSUE_PLACE = "identity_nfc.result.issue_place"
    const val RESULT_EXPIRE_DATE = "identity_nfc.result.expire_date"
    const val RESULT_IMAGE = "identity_nfc.result.image"
    const val RESULT_IMAGE_MIME_TYPE = "identity_nfc.result.image_mime_type"
    const val RESULT_DG1 = "identity_nfc.result.dg1"
    const val RESULT_DG2 = "identity_nfc.result.dg2"
    const val RESULT_DG13 = "identity_nfc.result.dg13"
    const val RESULT_DG14 = "identity_nfc.result.dg14"
    const val RESULT_SOD = "identity_nfc.result.sod"

    @Volatile
    var isOpen = false
  }

  private var nfcAdapter: NfcAdapter? = null

  private lateinit var progressBar: ProgressBar
  private lateinit var progressPercentTextView: TextView
  private lateinit var guideTextView: TextView
  private lateinit var cancelButton: Button
  private lateinit var retryButton: Button
  private lateinit var titleTextView: TextView
  private lateinit var uiText: NfcUiText

  @Volatile
  private var isReading = false

  @Volatile
  private var scanCompleted = false

  @Volatile
  private var isCanceled = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    isOpen = true

    setContentView(R.layout.activity_nfc)
    uiText = NfcUiText(uiLanguage)
    setupWindow()
    setupTransition()
    setupSheetAnimation()
    setupViews()

    nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    if (nfcAdapter == null) {
      val error = NfcCoreErrorMapper.localize(NfcCoreErrorMapper.nfcNotSupported(), uiLanguage)
      Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
      showError(error)
      finishWithError(error)
      return
    }

    if (nfcAdapter?.isEnabled != true) {
      val error = NfcCoreErrorMapper.localize(NfcCoreErrorMapper.nfcDisabled(), uiLanguage)
      Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
      showError(error)
      startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
      return
    }

    updateProgress(0, uiText.guideDefault)
  }

  override fun onResume() {
    super.onResume()
    if (!scanCompleted && nfcAdapter?.isEnabled == true) enableReaderMode()
  }

  override fun onPause() {
    super.onPause()
    nfcAdapter?.disableReaderMode(this)
  }

  override fun onDestroy() {
    isOpen = false
    isReading = false
    scanCompleted = false
    isCanceled = false
    super.onDestroy()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (isReading || scanCompleted) return

    @Suppress("DEPRECATION")
    val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
    startChipRead(tag)
  }

  private fun enableReaderMode() {
    val flags =
      NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

    nfcAdapter?.enableReaderMode(
      this,
      { tag ->
        if (!isReading && !scanCompleted) startChipRead(tag)
      },
      flags,
      null,
    )
  }

  private fun startChipRead(tag: Tag) {
    isReading = true
    isCanceled = false
    val language = uiLanguage

    Thread {
      try {
        val citizenId = intent
          ?.getStringExtra(EXTRA_CITIZEN_ID)
          ?.trim()
          .orEmpty()
        if (citizenId.length < 6) {
          val error = NfcCoreErrorMapper.localize(NfcCoreErrorMapper.invalidCitizenId(), language)
          showError(error)
          finishWithError(error)
          isReading = false
          return@Thread
        }

        val result = NfcCore.read(
          tag,
          NfcScanRequest(
            citizenId = citizenId,
            readImage = intent?.getBooleanExtra(EXTRA_READ_IMAGE, true) ?: true,
            cachePolicy = NfcCachePolicy.fromWireValue(
              intent?.getStringExtra(EXTRA_CACHE_POLICY).orEmpty(),
            ),
            language = language,
          ),
        ) { progress, message ->
          if (!isCanceled) updateProgress(progress, message)
        }

        if (isCanceled) {
          isReading = false
          return@Thread
        }

        scanCompleted = true
        vibrate(200)
        finishWithSuccess(result)
      } catch (error: NfcCoreException) {
        if (!isCanceled) showError(NfcCoreErrorMapper.toPayload(error, language))
        isReading = false
      } catch (error: Throwable) {
        if (!isCanceled) showError(NfcCoreErrorMapper.toPayload(error, language))
        isReading = false
      }
    }.start()
  }

  private fun setupWindow() {
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    window.setLayout(
      android.view.WindowManager.LayoutParams.MATCH_PARENT,
      android.view.WindowManager.LayoutParams.MATCH_PARENT,
    )
    window.setGravity(Gravity.BOTTOM)
  }

  private fun setupTransition() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
    } else {
      @Suppress("DEPRECATION")
      overridePendingTransition(0, 0)
    }
  }

  private fun setupSheetAnimation() {
    val sheetScrim = findViewById<View>(R.id.sheetScrim)
    val sheetContainer = findViewById<View>(R.id.sheetContainer)
    sheetScrim.alpha = 0f
    sheetContainer.post {
      sheetContainer.translationY = sheetContainer.height.toFloat()
      sheetScrim.animate().alpha(1f).setDuration(300).start()
      sheetContainer.animate()
        .translationY(0f)
        .setDuration(300)
        .setInterpolator(android.view.animation.DecelerateInterpolator())
        .start()
    }
    sheetScrim.setOnClickListener { if (!isReading) cancelScan() }
  }

  private fun setupViews() {
    progressBar = findViewById(R.id.nfcProgressBar)
    progressPercentTextView = findViewById(R.id.tvProgressPercent)
    guideTextView = findViewById(R.id.tvGuide)
    titleTextView = findViewById(R.id.tvTitle)
    cancelButton = findViewById(R.id.btnCancel)
    retryButton = findViewById(R.id.btnRetry)

    titleTextView.text = uiText.readyToScan
    cancelButton.text = uiText.cancel
    retryButton.text = uiText.retry
    cancelButton.setOnClickListener { cancelScan() }
    retryButton.setOnClickListener {
      isReading = false
      scanCompleted = false
      isCanceled = false
      progressBar.progress = 0
      retryButton.visibility = View.GONE
      updateProgress(0, uiText.guideDefault)
      enableReaderMode()
      vibrate(120)
    }
  }

  private fun updateProgress(progress: Int, message: String) {
    runOnUiThread {
      progressBar.progress = progress.coerceIn(0, 100)
      progressPercentTextView.text = "$progress%"
      guideTextView.text = if (message.isNotBlank()) message else uiText.guideDefault
      guideTextView.setTextColor(Color.parseColor("#6B7280"))
      cancelButton.visibility = View.VISIBLE
      retryButton.visibility = View.GONE
    }
    IdentityNfcEvents.progress(progress, message)
  }

  private fun showError(error: NfcCoreErrorPayload) {
    runOnUiThread {
      progressPercentTextView.text = "--%"
      guideTextView.text = error.message
      guideTextView.setTextColor(Color.parseColor("#C62828"))
      cancelButton.visibility = View.GONE
      retryButton.visibility = View.VISIBLE
    }
    IdentityNfcEvents.error(error.code, error.message)
  }

  private fun cancelScan() {
    if (scanCompleted || isCanceled) return
    isCanceled = true
    isReading = false
    val error = NfcCoreErrorMapper.localize(NfcCoreErrorMapper.userCanceled(), uiLanguage)
    showError(error)
    window.decorView.postDelayed({ finishWithError(error) }, 150)
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    cancelScan()
  }

  private fun finishWithSuccess(result: NfcScanResult) {
    runOnUiThread {
      nfcAdapter?.disableReaderMode(this)
      setResult(
        RESULT_OK,
        Intent().apply {
          putExtra(RESULT_CITIZEN_ID, result.citizenId)
          putExtra(RESULT_FULL_NAME, result.fullName)
          putExtra(RESULT_DOB, result.dob)
          putExtra(RESULT_GENDER, result.gender)
          putExtra(RESULT_NATIONALITY, result.nationality)
          putExtra(RESULT_PERMANENT_ADDRESS, result.permanentAddress)
          putExtra(RESULT_ISSUE_DATE, result.issueDate)
          putExtra(RESULT_ISSUE_PLACE, result.issuePlace)
          putExtra(RESULT_EXPIRE_DATE, result.expireDate)
          putExtra(RESULT_IMAGE, result.imageFromChipBytes)
          putExtra(RESULT_IMAGE_MIME_TYPE, result.chipImageMimeType)
          putExtra(RESULT_DG1, result.dg1Bytes)
          putExtra(RESULT_DG2, result.dg2Bytes)
          putExtra(RESULT_DG13, result.dg13Bytes)
          putExtra(RESULT_DG14, result.dg14Bytes)
          putExtra(RESULT_SOD, result.sodBytes)
        },
      )
      window.decorView.postDelayed({ finish() }, 800)
    }
  }

  private fun finishWithError(error: NfcCoreErrorPayload) {
    runOnUiThread {
      nfcAdapter?.disableReaderMode(this)
      setResult(
        RESULT_CANCELED,
        Intent().apply {
          putExtra(EXTRA_ERROR_CODE, error.code)
          putExtra(EXTRA_ERROR_MESSAGE, error.message)
        },
      )
      finish()
    }
  }

  private val uiLanguage: String
    get() = intent?.getStringExtra(EXTRA_LANGUAGE) ?: "en"

  private fun vibrate(durationMs: Long) {
    try {
      @Suppress("DEPRECATION")
      val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(durationMs)
      }
    } catch (_: Exception) {
      // Vibration is best-effort and must not affect scanning.
    }
  }

  override fun finish() {
    val sheetScrim = findViewById<View>(R.id.sheetScrim)
    val sheetContainer = findViewById<View>(R.id.sheetContainer)
    if (sheetScrim != null && sheetContainer != null) {
      sheetScrim.animate().alpha(0f).setDuration(250).start()
      sheetContainer.animate()
        .translationY(sheetContainer.height.toFloat())
        .setDuration(250)
        .setInterpolator(android.view.animation.AccelerateInterpolator())
        .withEndAction {
          super.finish()
          suppressTransition()
        }
        .start()
    } else {
      super.finish()
      suppressTransition()
    }
  }

  private fun suppressTransition() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    } else {
      @Suppress("DEPRECATION")
      overridePendingTransition(0, 0)
    }
  }
}
