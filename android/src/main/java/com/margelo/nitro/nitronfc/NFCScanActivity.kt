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
import com.margelo.nitro.nitronfc.utils.ChipReadErrorMapper
import com.margelo.nitro.nitronfc.utils.ChipReadErrorPayload
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class NFCScanActivity : AppCompatActivity() {
  companion object {
    private const val GUIDE_TEXT_DEFAULT =
      "Đưa CCCD sát mặt lưng điện thoại"
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
    setupWindow()
    setupTransition()
    setupSheetAnimation()
    setupViews()

    ensureBouncyCastleProvider()

    nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    if (nfcAdapter == null) {
      val error = ChipReadErrorMapper.nfcNotSupported()
      Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
      showError(error)
      NitroNfcRuntime.fail(error)
      finish()
      return
    }

    if (nfcAdapter?.isEnabled != true) {
      val error = ChipReadErrorMapper.nfcDisabled()
      Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
      showError(error)
      startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
      return
    }

    updateProgress(0, GUIDE_TEXT_DEFAULT)
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
      NitroNfcRuntime.cancelIfPending(ChipReadErrorMapper.userCanceled())
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

    Thread {
      try {
        val citizenId = intent
          ?.getStringExtra(NitroNfcRuntime.EXTRA_DOCUMENT_NUMBER)
          ?.trim()
          ?.takeIf { it.isNotBlank() }
          ?: NitroNfcRuntime.documentNumber.trim()

        if (citizenId.length < 6) {
          val error = ChipReadErrorMapper.invalidCitizenId()
          showError(error)
          NitroNfcRuntime.fail(error)
          Log.e("NitroNfc", "startChipRead: invalid citizenId")
          isReading = false
          return@Thread
        }

        val result = ChipReader.read(tag, citizenId) { progress, message ->
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
      } catch (e: ChipReadException) {
        if (isCanceled) {
          isReading = false
          return@Thread
        }
        val error = ChipReadErrorMapper.toPayload(e)
        Log.e("NitroNfc", "Chip read failed: ${error.message}")
        showError(error)
        isReading = false
      } catch (e: Exception) {
        if (isCanceled) {
          isReading = false
          return@Thread
        }
        val error = ChipReadErrorMapper.toPayload(e)
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
    cancelButton = findViewById(R.id.btnCancel)
    retryButton = findViewById(R.id.btnRetry)

    cancelButton.setOnClickListener {
      cancelScan()
    }

    retryButton.setOnClickListener {
      isReading = false
      scanCompleted = false
      isCanceled = false
      progressBar.progress = 0
      retryButton.visibility = View.GONE
      updateProgress(0, GUIDE_TEXT_DEFAULT)
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
        guideTextView.text = GUIDE_TEXT_DEFAULT
      }

      guideTextView.setTextColor(Color.parseColor(GUIDE_TEXT_COLOR_DEFAULT))
      cancelButton.visibility = View.VISIBLE
      retryButton.visibility = View.GONE
    }

    NitroNfcRuntime.emitProgress(progress, message)
  }

  private fun showError(error: ChipReadErrorPayload) {
    runOnUiThread {
      progressPercentTextView.text = "--%"
      guideTextView.text = error.message
      guideTextView.setTextColor(Color.parseColor(GUIDE_TEXT_COLOR_ERROR))
      cancelButton.visibility = View.GONE
      retryButton.visibility = View.VISIBLE
    }

    emitError(error)
  }

  private fun emitError(error: ChipReadErrorPayload) {
    NitroNfcRuntime.emitError(error)
  }

  private fun cancelScan() {
    if (scanCompleted || isCanceled) return

    isCanceled = true
    isReading = false
    showError(ChipReadErrorMapper.userCanceled())
    NitroNfcRuntime.cancelIfPending(ChipReadErrorMapper.userCanceled())

    runOnUiThread {
      window.decorView.postDelayed({ finish() }, 150)
    }
  }

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

  private fun ensureBouncyCastleProvider() {
    val existingProvider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
    if (existingProvider != null && existingProvider.javaClass.name == BouncyCastleProvider::class.java.name) {
      return
    }

    if (existingProvider != null) {
      Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    }

    Security.insertProviderAt(BouncyCastleProvider(), 1)
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
