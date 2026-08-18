package com.vppos.nfc.core

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.vppos.nfc.core.utils.NfcCoreErrorMapper
import com.vppos.nfc.core.utils.NfcCoreErrorPayload
import com.vppos.nfc.core.utils.NfcUiText
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Callbacks emitted by the reusable native NFC scan screen.
 *
 * The callback is deliberately Android-only and does not reference React
 * Native, Flutter, or WebView APIs. A host adapts these events to its own
 * promise/event mechanism.
 */
interface NfcScanUiListener {
  fun onProgress(progress: Int, message: String) = Unit
  fun onRecoverableError(error: NfcCoreErrorPayload) = Unit
  fun onSuccess(result: NfcScanResult) = Unit
  fun onCancelled(error: NfcCoreErrorPayload) = Unit
  fun onFailure(error: NfcCoreErrorPayload) = Unit
}

/**
 * Starts the core-owned NFC scan UI.
 *
 * Only one screen can be active in a process. The listener remains alive for
 * the lifetime of that screen, so hosts should not capture an Activity/View
 * that can outlive the scan. Callbacks are delivered on the main thread.
 */
object NfcScanUi {
  private const val EXTRA_CITIZEN_ID = "com.vppos.nfc.core.CITIZEN_ID"
  private const val EXTRA_READ_IMAGE = "com.vppos.nfc.core.READ_IMAGE"
  private const val EXTRA_CACHE_POLICY = "com.vppos.nfc.core.CACHE_POLICY"
  private const val EXTRA_LANGUAGE = "com.vppos.nfc.core.LANGUAGE"

  private val lock = Any()
  private val mainHandler = Handler(Looper.getMainLooper())
  private var activeSession: Session? = null

  @Volatile
  var isOpen: Boolean = false
    internal set

  /**
   * Opens [NfcScanUiActivity] using [request]. Returns false when another scan
   * is already active; in that case [listener.onFailure] receives ScanInProgress.
   */
  fun start(context: Context, request: NfcScanRequest, listener: NfcScanUiListener): Boolean {
    val cleanRequest = request.copy(citizenId = request.citizenId.trim())
    if (cleanRequest.citizenId.length < 6) {
      mainHandler.post {
        listener.onFailure(NfcCoreErrorMapper.localize(NfcCoreErrorMapper.invalidCitizenId(), cleanRequest.language))
      }
      return false
    }

    synchronized(lock) {
      if (activeSession != null) {
        mainHandler.post {
          listener.onFailure(
            NfcCoreErrorPayload(
              code = "ScanInProgress",
              message = if (cleanRequest.language.equals("vi", ignoreCase = true)) {
                "Một phiên quét NFC đang chạy."
              } else {
                "An NFC scan is already running."
              },
            ),
          )
        }
        return false
      }
      activeSession = Session(cleanRequest, listener)
    }

    return try {
      val activityIntent = Intent(context, NfcScanUiActivity::class.java)
        .putExtra(EXTRA_CITIZEN_ID, cleanRequest.citizenId)
        .putExtra(EXTRA_READ_IMAGE, cleanRequest.readImage)
        .putExtra(EXTRA_CACHE_POLICY, cleanRequest.cachePolicy.wireValue)
        .putExtra(EXTRA_LANGUAGE, cleanRequest.language)

      if (context !is android.app.Activity) {
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(activityIntent)
      true
    } catch (error: Throwable) {
      clearSession()
      mainHandler.post { listener.onFailure(NfcCoreErrorMapper.toPayload(error, cleanRequest.language)) }
      false
    }
  }

  internal fun requestFrom(intent: Intent?): NfcScanRequest? {
    val session = synchronized(lock) { activeSession } ?: return null
    // Intent values are used only for restoring the Activity's configuration.
    // The in-memory request remains the source of truth for its listener.
    val citizenId = intent?.getStringExtra(EXTRA_CITIZEN_ID)?.trim().orEmpty()
    return if (citizenId.isBlank()) session.request else {
      NfcScanRequest(
        citizenId = citizenId,
        readImage = intent?.getBooleanExtra(EXTRA_READ_IMAGE, session.request.readImage) ?: session.request.readImage,
        cachePolicy = NfcCachePolicy.fromWireValue(
          intent?.getStringExtra(EXTRA_CACHE_POLICY) ?: session.request.cachePolicy.wireValue,
        ),
        language = intent?.getStringExtra(EXTRA_LANGUAGE) ?: session.request.language,
      )
    }
  }

  internal fun emitProgress(progress: Int, message: String) = dispatch { it.onProgress(progress, message) }
  internal fun emitRecoverableError(error: NfcCoreErrorPayload) = dispatch { it.onRecoverableError(error) }

  internal fun complete(result: NfcScanResult) {
    val listener = takeListener() ?: return
    mainHandler.post { listener.onSuccess(result) }
  }

  internal fun cancel(error: NfcCoreErrorPayload) {
    val listener = takeListener() ?: return
    mainHandler.post { listener.onCancelled(error) }
  }

  internal fun fail(error: NfcCoreErrorPayload) {
    val listener = takeListener() ?: return
    mainHandler.post { listener.onFailure(error) }
  }

  private fun dispatch(block: (NfcScanUiListener) -> Unit) {
    val listener = synchronized(lock) { activeSession?.listener } ?: return
    mainHandler.post { block(listener) }
  }

  private fun takeListener(): NfcScanUiListener? = synchronized(lock) {
    activeSession?.listener.also { activeSession = null }
  }

  private fun clearSession() {
    synchronized(lock) { activeSession = null }
  }

  private data class Session(
    val request: NfcScanRequest,
    val listener: NfcScanUiListener,
  )
}

/**
 * Bottom-sheet NFC scan UI shared by Android native, React Native and WebView
 * hosts. NFC protocol operations remain in [NfcCore].
 */
open class NfcScanUiActivity : AppCompatActivity() {
  private enum class ScanUiState { WAITING_FOR_TAG, READING, RETRY_AVAILABLE, NFC_SETTINGS_REQUIRED, SUCCESS, CLOSING }

  private var nfcAdapter: NfcAdapter? = null
  private lateinit var request: NfcScanRequest
  private lateinit var progressBar: ProgressBar
  private lateinit var progressPercentTextView: TextView
  private lateinit var guideTextView: TextView
  private lateinit var cancelButton: Button
  private lateinit var retryButton: Button
  private lateinit var openSettingsButton: Button
  private lateinit var titleTextView: TextView
  private lateinit var hintImageView: ImageView
  private lateinit var uiText: NfcUiText

  private val tagClaimed = AtomicBoolean(false)
  private val activeAttemptId = AtomicLong(0)
  @Volatile private var activeCancellationSignal: NfcScanCancellationSignal? = null
  private val readerModeDisableDeferred = AtomicBoolean(false)
  private val readExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "NfcCore-ui-read").apply { isDaemon = true }
  }

  @Volatile private var uiState = ScanUiState.WAITING_FOR_TAG
  @Volatile private var latestProgress = 0
  private var lastEmittedProgress: Pair<Int, String>? = null
  private var lastAnnouncedGuide = ""
  private var progressAnimator: ObjectAnimator? = null
  private var progressAnimationTarget: Int? = null
  private var finishAnimationStarted = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val configuredRequest = NfcScanUi.requestFrom(intent)
    if (configuredRequest == null) {
      finish()
      return
    }
    request = configuredRequest
    NfcScanUi.isOpen = true
    setContentView(R.layout.activity_nfc)
    uiText = NfcUiText(request.language)
    setupWindow()
    setupTransition()
    setupViews()
    setupBackHandling()
    setupSheetAnimation()

    nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    when {
      nfcAdapter == null -> showTerminalError(NfcCoreErrorMapper.localize(NfcCoreErrorMapper.nfcNotSupported(), request.language))
      nfcAdapter?.isEnabled != true -> showNfcDisabled(emitEvent = true)
      else -> resetToWaitingForTag(announce = false)
    }
  }

  override fun onResume() {
    super.onResume()
    val adapter = nfcAdapter ?: return
    if (uiState == ScanUiState.CLOSING || uiState == ScanUiState.SUCCESS) return
    if (!adapter.isEnabled) {
      if (uiState != ScanUiState.NFC_SETTINGS_REQUIRED) showNfcDisabled(emitEvent = true)
      return
    }
    if (uiState == ScanUiState.NFC_SETTINGS_REQUIRED) resetToWaitingForTag(announce = true)
    if (uiState == ScanUiState.WAITING_FOR_TAG) enableReaderMode()
  }

  override fun onPause() {
    if (uiState == ScanUiState.READING) {
      cancelScan()
    } else if (!readerModeDisableDeferred.get()) {
      nfcAdapter?.disableReaderMode(this)
    }
    super.onPause()
  }

  override fun onDestroy() {
    super.onDestroy()
    val shouldCancel = uiState != ScanUiState.SUCCESS && !finishAnimationStarted
    activeAttemptId.incrementAndGet()
    tagClaimed.set(false)
    NfcScanUi.isOpen = false
    progressAnimator?.cancel()
    activeCancellationSignal?.let { cancellationSignal ->
      readerModeDisableDeferred.set(true)
      cancellationSignal.cancel()
    }
    readExecutor.shutdownNow()
    if (shouldCancel) NfcScanUi.cancel(NfcCoreErrorMapper.localize(NfcCoreErrorMapper.userCanceled(), request.language))
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (uiState != ScanUiState.WAITING_FOR_TAG) return
    @Suppress("DEPRECATION")
    val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
    startChipRead(tag)
  }

  private fun enableReaderMode() {
    if (uiState != ScanUiState.WAITING_FOR_TAG || nfcAdapter?.isEnabled != true) return
    nfcAdapter?.enableReaderMode(
      this,
      { tag -> if (uiState == ScanUiState.WAITING_FOR_TAG && !tagClaimed.get()) startChipRead(tag) },
      NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
      null,
    )
  }

  private fun startChipRead(tag: Tag) {
    if (uiState != ScanUiState.WAITING_FOR_TAG || !tagClaimed.compareAndSet(false, true)) return
    val attemptId = activeAttemptId.incrementAndGet()
    val cancellationSignal = NfcScanCancellationSignal()
    activeCancellationSignal = cancellationSignal
    uiState = ScanUiState.READING
    runOnUiThread {
      titleTextView.text = uiText.readyToScan
      cancelButton.visibility = View.VISIBLE
      retryButton.visibility = View.GONE
      openSettingsButton.visibility = View.GONE
      updateGuide(uiText.connecting, GUIDE_TEXT_COLOR_DEFAULT, announce = true)
    }
    readExecutor.execute {
      try {
        val result = NfcCore.read(tag, request, cancellationSignal) { progress, message ->
          if (isCurrentAttempt(attemptId)) updateProgress(progress, message)
        }
        if (!isCurrentAttempt(attemptId)) return@execute
        uiState = ScanUiState.SUCCESS
        vibrate(200)
        NfcScanUi.complete(result)
        runOnUiThread {
          renderSuccess()
          window.decorView.postDelayed({ finish() }, SUCCESS_VISIBLE_DURATION_MS)
        }
      } catch (error: Throwable) {
        if (!isCurrentAttempt(attemptId)) return@execute
        val payload = NfcCoreErrorMapper.toPayload(error, request.language)
        if (payload.code == "InvalidCitizenId" || payload.code == "NFCNotSupported") showTerminalError(payload) else showRecoverableError(payload)
      } finally {
        if (activeCancellationSignal === cancellationSignal) {
          activeCancellationSignal = null
        }
        completeDeferredReaderModeDisable()
      }
    }
  }

  private fun isCurrentAttempt(attemptId: Long) = uiState == ScanUiState.READING && activeAttemptId.get() == attemptId

  private fun resetToWaitingForTag(announce: Boolean) {
    uiState = ScanUiState.WAITING_FOR_TAG
    tagClaimed.set(false)
    activeAttemptId.incrementAndGet()
    latestProgress = 0
    lastEmittedProgress = null
    runOnUiThread {
      titleTextView.text = uiText.readyToScan
      cancelButton.text = uiText.cancel
      cancelButton.setOnClickListener { cancelScan() }
      cancelButton.visibility = View.VISIBLE
      retryButton.visibility = View.GONE
      openSettingsButton.visibility = View.GONE
      updateGuide(uiText.guideDefault, GUIDE_TEXT_COLOR_DEFAULT, announce)
      animateProgressTo(0)
    }
    emitProgressIfChanged(0, uiText.guideDefault)
  }

  private fun showNfcDisabled(emitEvent: Boolean) {
    val error = NfcCoreErrorMapper.localize(NfcCoreErrorMapper.nfcDisabled(), request.language)
    uiState = ScanUiState.NFC_SETTINGS_REQUIRED
    tagClaimed.set(false)
    runOnUiThread {
      titleTextView.text = uiText.readyToScan
      cancelButton.visibility = View.VISIBLE
      retryButton.visibility = View.GONE
      openSettingsButton.visibility = View.VISIBLE
      updateGuide(error.message, GUIDE_TEXT_COLOR_ERROR, announce = true)
    }
    if (emitEvent) NfcScanUi.emitRecoverableError(error)
  }

  private fun showRecoverableError(error: NfcCoreErrorPayload) {
    uiState = ScanUiState.RETRY_AVAILABLE
    activeAttemptId.incrementAndGet()
    tagClaimed.set(false)
    runOnUiThread {
      titleTextView.text = uiText.readyToScan
      cancelButton.visibility = View.VISIBLE
      retryButton.visibility = View.VISIBLE
      openSettingsButton.visibility = View.GONE
      updateGuide(error.message, GUIDE_TEXT_COLOR_ERROR, announce = true)
    }
    NfcScanUi.emitRecoverableError(error)
  }

  private fun showTerminalError(error: NfcCoreErrorPayload) {
    uiState = ScanUiState.CLOSING
    activeAttemptId.incrementAndGet()
    tagClaimed.set(false)
    runOnUiThread {
      titleTextView.text = uiText.readyToScan
      cancelButton.visibility = View.GONE
      retryButton.visibility = View.GONE
      openSettingsButton.visibility = View.GONE
      updateGuide(error.message, GUIDE_TEXT_COLOR_ERROR, announce = true)
      window.decorView.postDelayed({ finish() }, TERMINAL_ERROR_VISIBLE_DURATION_MS)
    }
    NfcScanUi.emitRecoverableError(error)
    NfcScanUi.fail(error)
  }

  private fun renderSuccess() {
    latestProgress = 100
    titleTextView.text = if (isVietnamese) "Quét thành công" else "Scan complete"
    cancelButton.text = if (isVietnamese) "Xong" else "Done"
    cancelButton.setOnClickListener { finish() }
    cancelButton.visibility = View.VISIBLE
    retryButton.visibility = View.GONE
    openSettingsButton.visibility = View.GONE
    updateGuide(uiText.completed, GUIDE_TEXT_COLOR_DEFAULT, announce = true)
    animateProgressTo(100)
    playSuccessAffordance()
  }

  private fun retryScan() {
    if (uiState != ScanUiState.RETRY_AVAILABLE) return
    vibrate(120)
    resetToWaitingForTag(announce = true)
    enableReaderMode()
  }

  private fun cancelScan() {
    if (uiState == ScanUiState.SUCCESS || uiState == ScanUiState.CLOSING || finishAnimationStarted) return
    uiState = ScanUiState.CLOSING
    activeAttemptId.incrementAndGet()
    tagClaimed.set(false)
    val cancellationSignal = activeCancellationSignal
    if (cancellationSignal == null) {
      nfcAdapter?.disableReaderMode(this)
    } else {
      readerModeDisableDeferred.set(true)
      cancellationSignal.cancel()
    }
    val error = NfcCoreErrorMapper.localize(NfcCoreErrorMapper.userCanceled(), request.language)
    NfcScanUi.emitRecoverableError(error)
    NfcScanUi.cancel(error)
    // finish() preserves the BottomSheet close animation. Do not wait before
    // starting it: the delay leaves this transparent Activity as the input owner
    // after the user has already cancelled, which makes the host UI feel frozen.
    finish()
  }

  private fun updateProgress(progress: Int, message: String) {
    val target = progress.coerceIn(0, 100).coerceAtLeast(latestProgress)
    latestProgress = target
    runOnUiThread {
      if (uiState == ScanUiState.READING || uiState == ScanUiState.SUCCESS) {
        animateProgressTo(target)
        val text = message.ifBlank { uiText.guideDefault }
        updateGuide(text, GUIDE_TEXT_COLOR_DEFAULT, announce = text != lastAnnouncedGuide)
      }
    }
    emitProgressIfChanged(target, message)
  }

  private fun emitProgressIfChanged(progress: Int, message: String) {
    val event = progress to message
    if (lastEmittedProgress == event) return
    lastEmittedProgress = event
    NfcScanUi.emitProgress(progress, message)
  }

  private fun animateProgressTo(target: Int) {
    if (progressAnimationTarget == target && progressAnimator?.isRunning == true) return
    val from = progressBar.progress
    progressAnimator?.cancel()
    progressAnimationTarget = null
    if (from == target || !animationsEnabled()) {
      renderProgressValue(target)
      return
    }
    progressAnimator = ObjectAnimator.ofInt(progressBar, "progress", from, target).apply {
      progressAnimationTarget = target
      duration = (abs(target - from) * 4L).coerceIn(MIN_PROGRESS_ANIMATION_MS, MAX_PROGRESS_ANIMATION_MS)
      addUpdateListener { renderProgressValue(it.animatedValue as Int) }
      start()
    }
  }

  private fun playSuccessAffordance() {
    if (!animationsEnabled()) return
    hintImageView.animate().cancel()
    hintImageView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(SUCCESS_PULSE_IN_DURATION_MS).withEndAction {
      hintImageView.animate().scaleX(1f).scaleY(1f).setDuration(SUCCESS_PULSE_OUT_DURATION_MS).start()
    }.start()
  }

  private fun renderProgressValue(progress: Int) {
    progressBar.progress = progress
    progressPercentTextView.text = "$progress%"
    progressBar.contentDescription = "$progress%"
  }

  private fun updateGuide(message: String, color: String, announce: Boolean) {
    guideTextView.text = message
    guideTextView.setTextColor(Color.parseColor(color))
    if (announce && message != lastAnnouncedGuide) {
      lastAnnouncedGuide = message
      guideTextView.post { guideTextView.announceForAccessibility(message) }
    }
  }

  private fun animationsEnabled() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

  private fun setupWindow() {
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
    window.setGravity(Gravity.BOTTOM)
  }

  private fun setupTransition() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
    else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
  }

  private fun setupSheetAnimation() {
    val scrim = findViewById<View>(R.id.sheetScrim)
    val sheet = findViewById<View>(R.id.sheetContainer)
    scrim.alpha = 0f

    // The XML starts this View invisible. Its height is unknown in onCreate,
    // so placing it below the screen in a layout callback guarantees that the
    // first drawn frame cannot show it at translationY = 0 before the opening
    // animation begins.
    val prepareSheet = object : View.OnLayoutChangeListener {
      override fun onLayoutChange(
        view: View,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int,
      ) {
        if (sheet.height <= 0) return
        sheet.removeOnLayoutChangeListener(this)
        sheet.translationY = sheet.height.toFloat()
        sheet.visibility = View.VISIBLE

        if (!animationsEnabled()) {
          scrim.alpha = 1f
          sheet.translationY = 0f
          return
        }

        // The view is already off-screen when this runs. post only defers the
        // animation, not the initial visual state, preventing an opening flash.
        sheet.post {
          if (finishAnimationStarted) return@post
          scrim.animate().alpha(1f).setDuration(SHEET_OPEN_DURATION_MS).start()
          sheet.animate()
            .translationY(0f)
            .setDuration(SHEET_OPEN_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        }
      }
    }
    sheet.addOnLayoutChangeListener(prepareSheet)

    /*
     * A View that is INVISIBLE is still measured and laid out. The listener
     * above therefore runs before draw even on a cold Activity launch.
     */
    if (sheet.height > 0) {
      prepareSheet.onLayoutChange(sheet, sheet.left, sheet.top, sheet.right, sheet.bottom, 0, 0, 0, 0)
    }
    scrim.setOnClickListener {
      if (uiState == ScanUiState.SUCCESS) finish() else cancelScan()
    }
  }

  private fun setupViews() {
    progressBar = findViewById(R.id.nfcProgressBar)
    progressPercentTextView = findViewById(R.id.tvProgressPercent)
    guideTextView = findViewById(R.id.tvGuide)
    titleTextView = findViewById(R.id.tvTitle)
    cancelButton = findViewById(R.id.btnCancel)
    retryButton = findViewById(R.id.btnRetry)
    openSettingsButton = findViewById(R.id.btnOpenNfcSettings)
    hintImageView = findViewById(R.id.ivNfcHint)
    titleTextView.text = uiText.readyToScan
    cancelButton.text = uiText.cancel
    retryButton.text = uiText.retry
    openSettingsButton.text = if (isVietnamese) "Mở cài đặt NFC" else "Open NFC settings"
    hintImageView.contentDescription = uiText.guideDefault
    cancelButton.setOnClickListener { cancelScan() }
    retryButton.setOnClickListener { retryScan() }
    openSettingsButton.setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
  }

  private fun setupBackHandling() {
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() { if (uiState == ScanUiState.SUCCESS) finish() else cancelScan() }
    })
  }

  override fun finish() {
    if (finishAnimationStarted) return
    if (uiState == ScanUiState.READING) {
      cancelScan()
      return
    }
    finishAnimationStarted = true
    uiState = ScanUiState.CLOSING
    if (!readerModeDisableDeferred.get()) {
      nfcAdapter?.disableReaderMode(this)
    }
    val scrim = findViewById<View>(R.id.sheetScrim)
    val sheet = findViewById<View>(R.id.sheetContainer)
    if (scrim != null && sheet != null && animationsEnabled()) {
      scrim.animate().alpha(0f).setDuration(SHEET_CLOSE_DURATION_MS).start()
      sheet.animate().translationY(sheet.height.toFloat()).setDuration(SHEET_CLOSE_DURATION_MS).setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
        super.finish()
        suppressTransition()
      }.start()
    } else {
      super.finish()
      suppressTransition()
    }
  }

  private fun suppressTransition() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
  }

  /** Runs after the NFC read exits, so it cannot block the Activity's UI thread. */
  private fun completeDeferredReaderModeDisable() {
    if (!readerModeDisableDeferred.compareAndSet(true, false)) return
    try {
      nfcAdapter?.disableReaderMode(this)
    } catch (_: Exception) {
      // Activity teardown must not replace the original NFC cancellation result.
    }
  }

  private val isVietnamese get() = request.language.equals("vi", ignoreCase = true)

  private fun vibrate(durationMs: Long) {
    try {
      @Suppress("DEPRECATION") val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
      else @Suppress("DEPRECATION") vibrator.vibrate(durationMs)
    } catch (_: Exception) { /* Optional haptics must not interrupt a scan. */ }
  }

  private companion object {
    const val GUIDE_TEXT_COLOR_DEFAULT = "#6B7280"
    const val GUIDE_TEXT_COLOR_ERROR = "#C62828"
    const val SHEET_OPEN_DURATION_MS = 300L
    const val SHEET_CLOSE_DURATION_MS = 250L
    const val MIN_PROGRESS_ANIMATION_MS = 120L
    const val MAX_PROGRESS_ANIMATION_MS = 260L
    const val SUCCESS_VISIBLE_DURATION_MS = 1_200L
    const val SUCCESS_PULSE_IN_DURATION_MS = 160L
    const val SUCCESS_PULSE_OUT_DURATION_MS = 180L
    const val TERMINAL_ERROR_VISIBLE_DURATION_MS = 700L
  }
}
