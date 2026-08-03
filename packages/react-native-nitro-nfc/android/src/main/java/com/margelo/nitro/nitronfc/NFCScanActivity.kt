package com.margelo.nitro.nitronfc

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
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
import com.identity.nfc.core.utils.NfcCoreErrorMapper
import com.identity.nfc.core.utils.NfcCoreErrorPayload
import com.identity.nfc.core.utils.NfcUiText

class NFCScanActivity : AppCompatActivity() {
  companion object {
    private const val GUIDE_TEXT_COLOR_DEFAULT = "#6B7280"
    private const val GUIDE_TEXT_COLOR_ERROR = "#C62828"

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

  // ── Lifecycle ──────────────────────────────────────────────

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    isOpen = true

    setContentView(R.layout.activity_nfc)
    uiText = NfcUiText(intent?.getStringExtra(NitroNfcRuntime.EXTRA_LANGUAGE) ?: "en")
    setupWindow()
    setupTransition()
    setupSheetAnimation()
    setupViews()

    nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    if (nfcAdapter == null) {
      val error = NfcCoreErrorMapper.localize(
        NfcCoreErrorMapper.nfcNotSupported(),
        intent?.getStringExtra(NitroNfcRuntime.EXTRA_LANGUAGE) ?: "en",
      )
      Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
      showError(error)
      NitroNfcRuntime.fail(error)
      finish()
      return
    }

    if (nfcAdapter?.isEnabled != true) {
      val error = NfcCoreErrorMapper.localize(
        NfcCoreErrorMapper.nfcDisabled(),
        intent?.getStringExtra(NitroNfcRuntime.EXTRA_LANGUAGE) ?: "en",
      )
      Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
      showError(error)
      startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
      return
    }

    updateProgress(0, uiText.guideDefault)
  }

  override fun onResume() {
    super.onResume()

    enableReaderMode()
  }

  override fun onPause() {
    super.onPause()
    nfcAdapter?.disableReaderMode(this)
  }

  override fun onDestroy() {
    super.onDestroy()
    val shouldCancelPendingScan = !scanCompleted
    isOpen = false
    isReading = false
    scanCompleted = false
    isCanceled = false

    if (shouldCancelPendingScan) {
      NitroNfcRuntime.cancelIfPending(
        NfcCoreErrorMapper.localize(NfcCoreErrorMapper.userCanceled(), uiLanguage),
      )
    }
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
        if (!isReading && !scanCompleted) {
          Log.d("NitroNfc", "Discovered tag techs=${tag.techList.joinToString()}")
          startChipRead(tag)
        }
      },
      flags,
      null,
    )
  }

  // ── Chip Reading ───────────────────────────────────────────

  private fun startChipRead(tag: Tag) {
    isReading = true
    isCanceled = false
    val language = uiLanguage

    Thread {
      try {
        val citizenId = intent
          ?.getStringExtra(NitroNfcRuntime.EXTRA_DOCUMENT_NUMBER)
          ?.trim()
          ?.takeIf { it.isNotBlank() }
          ?: NitroNfcRuntime.documentNumber.trim()

        val readImage = intent?.getBooleanExtra(
          NitroNfcRuntime.EXTRA_READ_IMAGE,
          true,
        ) ?: true
        val cachePolicy = intent?.getStringExtra(
          NitroNfcRuntime.EXTRA_CACHE_POLICY,
        ) ?: "fresh"
        if (citizenId.length < 6) {
          val error = NfcCoreErrorMapper.localize(
            NfcCoreErrorMapper.invalidCitizenId(),
            language,
          )
          showError(error)
          NitroNfcRuntime.fail(error)
          Log.e("NitroNfc", "startChipRead: invalid citizenId")
          isReading = false
          return@Thread
        }

        val result = NfcCore.read(
          tag,
          NfcScanRequest(
            citizenId = citizenId,
            readImage = readImage,
            cachePolicy = NfcCachePolicy.fromWireValue(cachePolicy),
            language = language,
          ),
        ) { progress, message ->
          if (!isCanceled) {
            updateProgress(progress, message)
          }
        }

        if (isCanceled) {
          isReading = false
          return@Thread
        }

        vibrate(200)
        scanCompleted = true
        NitroNfcRuntime.complete(result)

        runOnUiThread {
          window.decorView.postDelayed({ finish() }, 800)
        }
      } catch (e: NfcCoreException) {
        if (isCanceled) {
          isReading = false
          return@Thread
        }
        val error = NfcCoreErrorMapper.toPayload(e, language)
        Log.e("NitroNfc", "Chip read failed: ${error.message}")
        showError(error)
        isReading = false
      } catch (e: Exception) {
        if (isCanceled) {
          isReading = false
          return@Thread
        }
        val error = NfcCoreErrorMapper.toPayload(e, language)
        Log.e("NitroNfc", "NFC read failed", e)
        showError(error)
        isReading = false
      }
    }.start()
  }

  // ── UI Setup ───────────────────────────────────────────────

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

    sheetScrim.setOnClickListener {
      if (!isReading) finish()
    }
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

    cancelButton.setOnClickListener {
      cancelScan()
    }

    retryButton.setOnClickListener {
      isReading = false
      scanCompleted = false
      isCanceled = false
      progressBar.progress = 0
      retryButton.visibility = View.GONE
      updateProgress(0, uiText.guideDefault)
      vibrate(120)
    }
  }

  // ── Progress & Feedback ────────────────────────────────────

  private fun updateProgress(progress: Int, message: String) {
    runOnUiThread {
      progressBar.progress = progress
      progressPercentTextView.text = "$progress%"

      if (message.isNotBlank()) {
        guideTextView.text = message
      } else if (progress == 0) {
        guideTextView.text = uiText.guideDefault
      }

      guideTextView.setTextColor(Color.parseColor(GUIDE_TEXT_COLOR_DEFAULT))
      cancelButton.visibility = View.VISIBLE
      retryButton.visibility = View.GONE
    }

    NitroNfcRuntime.emitProgress(progress, message)
  }

  private fun showError(error: NfcCoreErrorPayload) {
    runOnUiThread {
      progressPercentTextView.text = "--%"
      guideTextView.text = error.message
      guideTextView.setTextColor(Color.parseColor(GUIDE_TEXT_COLOR_ERROR))
      cancelButton.visibility = View.GONE
      retryButton.visibility = View.VISIBLE
    }

    emitError(error)
  }

  private fun emitError(error: NfcCoreErrorPayload) {
    NitroNfcRuntime.emitError(error)
  }

  private fun cancelScan() {
    if (scanCompleted || isCanceled) return

    isCanceled = true
    isReading = false
    val error = NfcCoreErrorMapper.localize(
      NfcCoreErrorMapper.userCanceled(),
      uiLanguage,
    )
    showError(error)
    NitroNfcRuntime.cancelIfPending(error)

    runOnUiThread {
      window.decorView.postDelayed({ finish() }, 150)
    }
  }

  private val uiLanguage: String
    get() = intent?.getStringExtra(NitroNfcRuntime.EXTRA_LANGUAGE) ?: "en"

  private fun vibrate(durationMs: Long) {
    try {
      @Suppress("DEPRECATION")
      val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(
          VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE),
        )
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(durationMs)
      }
    } catch (_: Exception) {
    }
  }

  // ── Finish Animation ───────────────────────────────────────

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
