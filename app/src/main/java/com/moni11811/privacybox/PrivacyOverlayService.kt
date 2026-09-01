package com.moni11811.privacybox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.input.InputManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class PrivacyActivationState {
  INACTIVE,
  STARTING,
  ACTIVE,
  SUSPENDED,
  PAUSED,
  ERROR,
  BLOCKED,
}

class PrivacyOverlayService : Service() {
  private val serviceHandler = Handler(Looper.getMainLooper())
  private lateinit var windowManager: WindowManager
  private lateinit var surfaceParams: WindowManager.LayoutParams
  private lateinit var topControlsParams: WindowManager.LayoutParams
  private lateinit var bottomControlsParams: WindowManager.LayoutParams
  private var surfaceView: PrivacySurfaceView? = null
  private var topControlsView: PrivacyTopControlsView? = null
  private var bottomControlsView: PrivacyBottomControlsView? = null
  private val cornerHandleViews = mutableMapOf<ResizeCorner, PrivacyCornerHandleView>()
  private val cornerHandleParams = mutableMapOf<ResizeCorner, WindowManager.LayoutParams>()
  private var geometry = OverlayState.Geometry(0, 0, 1, 1)
  private var geometryOrientation = Configuration.ORIENTATION_UNDEFINED
  private var gestureStart = geometry
  private var locked = false
  private var lockedBeforePause = false
  private var paused = false
  private var resizing = false
  private var swapPauseAndStop = false
  private var keepPauseVisibleWhenLocked = false
  private var showLockIcon = false
  private var pausedIconStartX = 0
  private var pausedIconStartY = 0
  private var maximumObscuringOpacity = 0f
  private var loggedOverlayBounds = false
  private var teardownComplete = false
  private var failureMessage: String? = null
  private val reconcileWindowInsets = Runnable { reconcileGeometryWithCurrentInsets() }
  private val activationTimeout = Runnable {
    if (activationState == PrivacyActivationState.STARTING) {
      failClosed("Samsung privacy activation timed out. The overlay was removed.")
    }
  }

  override fun onCreate() {
    super.onCreate()
    activationState = PrivacyActivationState.STARTING
    createNotificationChannels()
    startForeground(NOTIFICATION_ID, ongoingNotification(PrivacyActivationState.STARTING))
    if (OverlayState.isCleanupBlocked(this)) {
      val message = "A previous privacy cleanup failed. Restart the device before retrying."
      failureMessage = message
      activationState = PrivacyActivationState.BLOCKED
      OverlayState.setLastError(this, message)
      showFailureNotification(message)
      stopSelf()
      return
    }
    if (!Settings.canDrawOverlays(this)) {
      failClosed("Display-over-other-apps permission is missing. The overlay was not started.")
      return
    }
    val privacyApi = privacyApiFactory().getOrElse {
      failClosed("Samsung privacy display is unavailable on this device or firmware.", it)
      return
    }
    runCatching { showOverlay(privacyApi) }
      .onFailure { failClosed("The privacy overlay could not be created and was removed.", it) }
      .onSuccess { serviceHandler.postDelayed(activationTimeout, ACTIVATION_TIMEOUT_MS) }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_RESUME -> resumeFromPause()
      ACTION_REFRESH_CONTROLS -> refreshControlPreferences()
    }
    return START_STICKY
  }

  override fun onDestroy() {
    serviceHandler.removeCallbacks(activationTimeout)
    val cleanupFailure = teardownOverlay()
    if (cleanupFailure != null && failureMessage == null) {
      val message = "Privacy cleanup did not complete normally. Restart the device before relying on privacy mode."
      failureMessage = message
      OverlayState.setLastError(this, message)
      OverlayState.markCleanupBlocked(this)
      activationState = PrivacyActivationState.BLOCKED
      showFailureNotification(message)
      Log.e(TAG, "Privacy cleanup failed", cleanupFailure)
    } else if (
      activationState != PrivacyActivationState.ERROR &&
      activationState != PrivacyActivationState.BLOCKED
    ) {
      activationState = PrivacyActivationState.INACTIVE
    }
    refreshTile()
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    serviceHandler.removeCallbacks(reconcileWindowInsets)
    serviceHandler.post(reconcileWindowInsets)
  }

  private fun showOverlay(privacyApi: PrivacyDisplayApi) {
    windowManager = getSystemService(WindowManager::class.java)
    maximumObscuringOpacity = getSystemService(InputManager::class.java)
      .maximumObscuringOpacityForTouch
      .coerceIn(0f, 1f)
    geometryOrientation = currentOverlayOrientation()
    val savedGeometry = OverlayState.geometry(this, geometryOrientation)
    geometry = sanitizeGeometry(savedGeometry.copy(y = maxOf(savedGeometry.y, statusBarInsetTop())))
    if (geometry != savedGeometry) OverlayState.saveGeometry(this, geometry, geometryOrientation)
    locked = OverlayState.isLocked(this)
    swapPauseAndStop = OverlayState.isPauseStopSwapped(this)
    keepPauseVisibleWhenLocked = OverlayState.keepPauseVisibleWhenLocked(this)
    showLockIcon = OverlayState.showLockIcon(this)
    val lockedPauseOnly = locked && keepPauseVisibleWhenLocked

    surfaceParams = overlayParams(
      width = geometry.width,
      height = geometry.height,
      x = geometry.x,
      y = geometry.y,
      touchable = !locked,
      title = "Privacy Surface",
      maximumObscuringOpacity = maximumObscuringOpacity,
    )
    topControlsParams = overlayParams(
      width = topControlsWidth(compact = lockedPauseOnly),
      height = topControlsHeight(compact = lockedPauseOnly),
      x = topControlsX(geometry, compact = lockedPauseOnly),
      y = topControlsY(geometry, compact = lockedPauseOnly),
      touchable = !locked || lockedPauseOnly,
      title = "Privacy Box Pause and Stop",
      maximumObscuringOpacity = maximumObscuringOpacity,
    ).apply {
      if (locked && !lockedPauseOnly) alpha = 0f
    }
    bottomControlsParams = overlayParams(
      width = bottomControlsWidth(),
      height = bottomControlsHeight(),
      x = bottomControlsX(geometry),
      y = bottomControlsY(geometry),
      touchable = showLockIcon,
      title = "Privacy Box Lock",
      maximumObscuringOpacity = maximumObscuringOpacity,
    ).apply {
      if (!showLockIcon) alpha = 0f
    }

    surfaceView = PrivacySurfaceView(
      context = this,
      privacyApi = privacyApi,
      initiallyLocked = locked,
      initiallyShowLockIcon = showLockIcon,
      onDragStart = {
        if (activationState == PrivacyActivationState.ACTIVE) {
          gestureStart = geometry
          surfaceView?.suspendPrivacyForMotion("move")
        }
      },
      onDrag = { dx, dy -> moveBy(dx, dy) },
      onDragEnd = {
        persistGeometry()
        surfaceView?.rearmPrivacyAfterMotion("move")
      },
      onPrivacyActivated = {
        if (!resizing) publishState(PrivacyActivationState.ACTIVE)
      },
      onPrivacySuspended = { publishState(PrivacyActivationState.SUSPENDED) },
      onPrivacyPaused = { enterPausedState() },
      onPrivacyFailure = { stage, error ->
        failClosed("Samsung privacy $stage failed. The overlay was removed.", error)
      },
    )
    topControlsView = PrivacyTopControlsView(
      context = this,
      initiallySwapped = swapPauseAndStop,
      initiallyLockedPauseOnly = lockedPauseOnly,
      onPause = { pauseOrResume() },
      onStop = { stopSelf() },
      onPausedDragStart = { beginPausedIconDrag() },
      onPausedDrag = { dx, dy -> movePausedIcon(dx, dy) },
      onPausedDragEnd = { persistPausedIconPosition() },
      isTopRightResizeTouch = { rawX, rawY ->
        isInsideCornerHandle(ResizeCorner.TOP_RIGHT, rawX, rawY)
      },
      onTopRightResizeStart = {
        surfaceView?.setActiveResizeCorner(ResizeCorner.TOP_RIGHT)
        beginResize()
      },
      onTopRightResize = { dx, dy -> resizeBy(ResizeCorner.TOP_RIGHT, dx, dy) },
      onTopRightResizeEnd = {
        finishResize()
        surfaceView?.setActiveResizeCorner(null)
      },
    ).apply {
      visibility = if (locked && !lockedPauseOnly) View.INVISIBLE else View.VISIBLE
    }
    bottomControlsView = PrivacyBottomControlsView(
      context = this,
      initiallyLocked = locked,
      initiallyShowIcon = showLockIcon,
      onPressedChanged = { pressed -> surfaceView?.setLockPressActive(pressed) },
      onLockChanged = {
        if (activationState == PrivacyActivationState.ACTIVE) setLocked(it)
        else bottomControlsView?.setLocked(locked)
      },
    ).apply {
      visibility = if (showLockIcon) View.VISIBLE else View.INVISIBLE
    }
    val cornerHandlesEnabled = !locked || !showLockIcon
    ResizeCorner.entries.forEach { corner ->
      val handleSize = cornerHandleSize()
      cornerHandleParams[corner] = overlayParams(
        width = handleSize,
        height = handleSize,
        x = cornerHandleX(geometry, corner),
        y = cornerHandleY(geometry, corner),
        touchable = cornerHandlesEnabled,
        title = "Privacy Box ${corner.name.lowercase()} resize handle",
        maximumObscuringOpacity = maximumObscuringOpacity,
      ).apply {
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!cornerHandlesEnabled) alpha = 0f
      }
      cornerHandleViews[corner] = PrivacyCornerHandleView(
        context = this,
        corner = corner,
        onPressedChanged = { active -> surfaceView?.setActiveResizeCorner(if (active) corner else null) },
        onResizeStart = { beginResize() },
        onResize = { dx, dy -> resizeBy(corner, dx, dy) },
        onResizeEnd = { finishResize() },
        onLockToggle = { setLocked(!locked) },
      ).apply {
        setInteractionState(locked = locked, cornerLockingEnabled = !showLockIcon)
        visibility = if (cornerHandlesEnabled) View.VISIBLE else View.INVISIBLE
      }
    }
    surfaceView?.setOnApplyWindowInsetsListener { _, insets ->
      serviceHandler.removeCallbacks(reconcileWindowInsets)
      serviceHandler.post(reconcileWindowInsets)
      insets
    }

    windowManager.addView(surfaceView, surfaceParams)
    ResizeCorner.entries.forEach { corner ->
      windowManager.addView(cornerHandleViews.getValue(corner), cornerHandleParams.getValue(corner))
    }
    windowManager.addView(topControlsView, topControlsParams)
    windowManager.addView(bottomControlsView, bottomControlsParams)
    surfaceView?.schedulePrivacyRefresh("attach")
    Log.i(TAG, "Overlay added geometry=$geometry locked=$locked surfaceTouchable=${!locked}")
  }

  private fun moveBy(dx: Int, dy: Int) {
    if (locked || activationState != PrivacyActivationState.SUSPENDED) return
    val (availableWidth, availableHeight) = availableOverlaySize()
    val edge = edgeInset()
    val minimumX = minimumOverlayX()
    val minimumY = statusBarInsetTop()
    val maxX = (availableWidth - gestureStart.width - edge).coerceAtLeast(minimumX)
    val maxY = (availableHeight - gestureStart.height - edge).coerceAtLeast(minimumY)
    applyGeometry(
      gestureStart.copy(
        x = (gestureStart.x + dx).coerceIn(minimumX, maxX),
        y = (gestureStart.y + dy).coerceIn(minimumY, maxY),
      ),
      reason = "move",
    )
  }

  private fun resizeBy(corner: ResizeCorner, dx: Int, dy: Int) {
    if (locked || !resizing || activationState != PrivacyActivationState.SUSPENDED) return
    val (availableWidth, availableHeight) = availableOverlaySize()
    val edge = edgeInset()
    val minWidth = dp(MIN_WIDTH_DP)
    val minHeight = dp(MIN_HEIGHT_DP)
    applyGeometry(
      OverlayGeometryPolicy.resize(
        start = gestureStart,
        corner = corner,
        dx = dx,
        dy = dy,
        availableWidth = availableWidth,
        availableHeight = availableHeight,
        edgeInset = edge,
        minimumX = minimumOverlayX(),
        minimumY = statusBarInsetTop(),
        minimumWidth = minWidth,
        minimumHeight = minHeight,
      ),
      reason = "resize",
    )
  }

  private fun beginResize() {
    if (locked || activationState != PrivacyActivationState.ACTIVE) return
    gestureStart = geometry
    resizing = true
    surfaceView?.suspendPrivacyForMotion("resize")
    if (activationState != PrivacyActivationState.SUSPENDED) resizing = false
  }

  private fun finishResize() {
    if (!resizing) return
    persistGeometry()
    resizing = false
    surfaceView?.rearmPrivacyAfterMotion("resize")
  }

  private fun applyGeometry(next: OverlayState.Geometry, reason: String) {
    geometry = next
    val compactTopControls = paused || (locked && keepPauseVisibleWhenLocked)
    surfaceParams.x = next.x
    surfaceParams.y = next.y
    surfaceParams.width = next.width
    surfaceParams.height = next.height
    if (!paused) {
      topControlsParams.x = topControlsX(next, compactTopControls)
      topControlsParams.y = topControlsY(next, compactTopControls)
    }
    bottomControlsParams.x = bottomControlsX(next)
    bottomControlsParams.y = bottomControlsY(next)
    surfaceView?.let { windowManager.updateViewLayout(it, surfaceParams) }
    ResizeCorner.entries.forEach { corner ->
      val params = cornerHandleParams[corner] ?: return@forEach
      params.x = cornerHandleX(next, corner)
      params.y = cornerHandleY(next, corner)
      cornerHandleViews[corner]?.let { windowManager.updateViewLayout(it, params) }
    }
    topControlsView?.let { windowManager.updateViewLayout(it, topControlsParams) }
    bottomControlsView?.let { windowManager.updateViewLayout(it, bottomControlsParams) }
    surfaceView?.schedulePrivacyRefresh(reason)
  }

  private fun setLocked(value: Boolean) {
    if (paused) return
    locked = value
    OverlayState.setLocked(this, value)
    val lockedPauseOnly = value && keepPauseVisibleWhenLocked
    val surfaceWindowState = OverlayWindowPolicy.surface(
      touchable = !value,
      maximumObscuringOpacity = maximumObscuringOpacity,
    )
    surfaceParams.flags = surfaceWindowState.flags
    surfaceParams.alpha = surfaceWindowState.alpha
    val topWindowState = OverlayWindowPolicy.surface(
      touchable = !value || lockedPauseOnly,
      maximumObscuringOpacity = maximumObscuringOpacity,
    )
    topControlsParams.flags = topWindowState.flags
    topControlsParams.alpha = if (value && !lockedPauseOnly) 0f else topWindowState.alpha
    topControlsParams.width = topControlsWidth(compact = lockedPauseOnly)
    topControlsParams.height = topControlsHeight(compact = lockedPauseOnly)
    topControlsParams.x = topControlsX(geometry, compact = lockedPauseOnly)
    topControlsParams.y = topControlsY(geometry, compact = lockedPauseOnly)
    topControlsView?.apply {
      setLockedPauseOnly(lockedPauseOnly)
      visibility = if (value && !lockedPauseOnly) View.INVISIBLE else View.VISIBLE
    }
    bottomControlsParams.width = bottomControlsWidth()
    bottomControlsParams.height = bottomControlsHeight()
    bottomControlsParams.x = bottomControlsX(geometry)
    bottomControlsParams.y = bottomControlsY(geometry)
    surfaceView?.setLocked(value)
    surfaceView?.setShowLockIcon(showLockIcon)
    bottomControlsView?.setLocked(value)
    bottomControlsView?.setShowIcon(showLockIcon)
    surfaceView?.let { windowManager.updateViewLayout(it, surfaceParams) }
    updateLockControlMode()
    setCornerHandlesEnabled(!value || !showLockIcon)
    topControlsView?.let { windowManager.updateViewLayout(it, topControlsParams) }
    bottomControlsView?.let { windowManager.updateViewLayout(it, bottomControlsParams) }
    Log.i(TAG, "Movement lock changed locked=$value")
  }

  private fun setCornerHandlesEnabled(enabled: Boolean) {
    ResizeCorner.entries.forEach { corner ->
      val params = cornerHandleParams[corner] ?: return@forEach
      val handleState = OverlayWindowPolicy.surface(
        touchable = enabled,
        maximumObscuringOpacity = maximumObscuringOpacity,
      )
      params.flags = handleState.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
      params.alpha = if (enabled) handleState.alpha else 0f
      cornerHandleViews[corner]?.let { view ->
        view.setInteractionState(locked = locked, cornerLockingEnabled = !showLockIcon)
        view.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        windowManager.updateViewLayout(view, params)
      }
    }
  }

  private fun updateLockControlMode() {
    if (!::bottomControlsParams.isInitialized) return
    val enabled = showLockIcon && !paused
    val state = OverlayWindowPolicy.surface(
      touchable = enabled,
      maximumObscuringOpacity = maximumObscuringOpacity,
    )
    bottomControlsParams.flags = state.flags
    bottomControlsParams.alpha = if (enabled) state.alpha else 0f
    bottomControlsView?.apply {
      setShowIcon(showLockIcon)
      visibility = if (enabled) View.VISIBLE else View.INVISIBLE
      if (isAttachedToWindow) windowManager.updateViewLayout(this, bottomControlsParams)
    }
  }

  private fun refreshControlPreferences() {
    swapPauseAndStop = OverlayState.isPauseStopSwapped(this)
    keepPauseVisibleWhenLocked = OverlayState.keepPauseVisibleWhenLocked(this)
    showLockIcon = OverlayState.showLockIcon(this)
    topControlsView?.setSwapped(swapPauseAndStop)
    surfaceView?.setShowLockIcon(showLockIcon)
    if (!::windowManager.isInitialized || !::topControlsParams.isInitialized) return
    when {
      paused -> Unit
      locked -> setLocked(true)
      else -> {
        updateLockControlMode()
        setCornerHandlesEnabled(true)
      }
    }
  }

  private fun pauseOrResume() {
    when (activationState) {
      PrivacyActivationState.ACTIVE -> surfaceView?.pausePrivacy()
      PrivacyActivationState.PAUSED -> resumeFromPause()
      else -> Unit
    }
  }

  private fun enterPausedState() {
    lockedBeforePause = locked
    paused = true

    surfaceView?.visibility = View.INVISIBLE
    OverlayWindowPolicy.surface(touchable = false, maximumObscuringOpacity = maximumObscuringOpacity)
      .also {
        surfaceParams.flags = it.flags
        surfaceParams.alpha = 0f
      }

    topControlsView?.apply {
      setLockedPauseOnly(false)
      setPaused(true)
      visibility = View.VISIBLE
    }
    topControlsParams.width = topControlsWidth(compact = true)
    topControlsParams.height = topControlsHeight(compact = true)
    val defaultPausedPosition = Pair(
      topControlsX(geometry, compact = true),
      topControlsY(geometry, compact = true),
    )
    val pausedPosition = OverlayState.pausedIconPosition(this) ?: defaultPausedPosition
    val safePausedPosition = sanitizePausedIconPosition(pausedPosition.first, pausedPosition.second)
    topControlsParams.x = safePausedPosition.first
    topControlsParams.y = safePausedPosition.second
    OverlayWindowPolicy.surface(touchable = true, maximumObscuringOpacity = maximumObscuringOpacity)
      .also {
        topControlsParams.flags = it.flags
        topControlsParams.alpha = it.alpha
      }

    bottomControlsView?.visibility = View.INVISIBLE
    OverlayWindowPolicy.surface(touchable = false, maximumObscuringOpacity = maximumObscuringOpacity)
      .also {
        bottomControlsParams.flags = it.flags
        bottomControlsParams.alpha = 0f
      }

    surfaceView?.let { windowManager.updateViewLayout(it, surfaceParams) }
    topControlsView?.let { windowManager.updateViewLayout(it, topControlsParams) }
    bottomControlsView?.let { windowManager.updateViewLayout(it, bottomControlsParams) }
    setCornerHandlesEnabled(false)
    publishState(PrivacyActivationState.PAUSED)
  }

  private fun resumeFromPause() {
    if (!paused || activationState != PrivacyActivationState.PAUSED) return
    paused = false
    locked = lockedBeforePause
    activationState = PrivacyActivationState.STARTING

    surfaceView?.visibility = View.VISIBLE
    OverlayWindowPolicy.surface(touchable = !locked, maximumObscuringOpacity = maximumObscuringOpacity)
      .also {
        surfaceParams.flags = it.flags
        surfaceParams.alpha = it.alpha
      }

    val lockedPauseOnly = locked && keepPauseVisibleWhenLocked
    topControlsView?.apply {
      setLockedPauseOnly(lockedPauseOnly)
      setPaused(false)
      visibility = if (locked && !lockedPauseOnly) View.INVISIBLE else View.VISIBLE
    }
    topControlsParams.width = topControlsWidth(compact = lockedPauseOnly)
    topControlsParams.height = topControlsHeight(compact = lockedPauseOnly)
    topControlsParams.x = topControlsX(geometry, compact = lockedPauseOnly)
    topControlsParams.y = topControlsY(geometry, compact = lockedPauseOnly)
    OverlayWindowPolicy.surface(
      touchable = !locked || lockedPauseOnly,
      maximumObscuringOpacity = maximumObscuringOpacity,
    ).also {
      topControlsParams.flags = it.flags
      topControlsParams.alpha = if (locked && !lockedPauseOnly) 0f else it.alpha
    }

    bottomControlsView?.apply {
      setLocked(locked)
      setShowIcon(showLockIcon)
    }
    bottomControlsParams.width = bottomControlsWidth()
    bottomControlsParams.height = bottomControlsHeight()
    bottomControlsParams.x = bottomControlsX(geometry)
    bottomControlsParams.y = bottomControlsY(geometry)

    surfaceView?.setLocked(locked)
    surfaceView?.setShowLockIcon(showLockIcon)

    surfaceView?.let { windowManager.updateViewLayout(it, surfaceParams) }
    topControlsView?.let { windowManager.updateViewLayout(it, topControlsParams) }
    updateLockControlMode()
    setCornerHandlesEnabled(!locked || !showLockIcon)
    publishState(PrivacyActivationState.STARTING)
    serviceHandler.postDelayed(activationTimeout, ACTIVATION_TIMEOUT_MS)
    surfaceView?.resumePrivacy()
  }

  private fun beginPausedIconDrag() {
    if (activationState != PrivacyActivationState.PAUSED) return
    pausedIconStartX = topControlsParams.x
    pausedIconStartY = topControlsParams.y
  }

  private fun movePausedIcon(dx: Int, dy: Int) {
    if (activationState != PrivacyActivationState.PAUSED) return
    val position = sanitizePausedIconPosition(pausedIconStartX + dx, pausedIconStartY + dy)
    topControlsParams.x = position.first
    topControlsParams.y = position.second
    topControlsView?.let { windowManager.updateViewLayout(it, topControlsParams) }
  }

  private fun persistPausedIconPosition() {
    if (activationState != PrivacyActivationState.PAUSED) return
    OverlayState.savePausedIconPosition(this, topControlsParams.x, topControlsParams.y)
  }

  private fun sanitizePausedIconPosition(x: Int, y: Int): Pair<Int, Int> {
    val metrics = windowManager.currentWindowMetrics
    val bounds = metrics.bounds
    val systemBars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
    val navigationBars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
    val edge = edgeInset()
    val minimumX = bounds.left + edge
    val maximumX = (bounds.right - navigationBars.right - topControlsParams.width - edge)
      .coerceAtLeast(minimumX)
    val minimumY = bounds.top + systemBars.top + edge
    val maximumY = (bounds.bottom - navigationBars.bottom - topControlsParams.height - edge)
      .coerceAtLeast(minimumY)
    return Pair(
      x.coerceIn(minimumX, maximumX),
      y.coerceIn(minimumY, maximumY),
    )
  }

  private fun persistGeometry() {
    OverlayState.saveGeometry(this, geometry, geometryOrientation)
    Log.i(TAG, "Geometry saved $geometry")
  }

  private fun sanitizeGeometry(input: OverlayState.Geometry): OverlayState.Geometry {
    val (availableWidth, availableHeight) = availableOverlaySize()
    return OverlayGeometryPolicy.sanitize(
      input = input,
      availableWidth = availableWidth,
      availableHeight = availableHeight,
      edgeInset = edgeInset(),
      minimumX = minimumOverlayX(),
      minimumY = statusBarInsetTop(),
      minimumWidth = dp(MIN_WIDTH_DP),
      minimumHeight = dp(MIN_HEIGHT_DP),
    )
  }

  private fun reconcileGeometryWithCurrentInsets() {
    if (teardownComplete || !::windowManager.isInitialized || surfaceView == null) return
    val currentOrientation = currentOverlayOrientation()
    val orientationChanged = currentOrientation != geometryOrientation
    val candidate = if (orientationChanged) {
      OverlayState.geometry(this, currentOrientation)
    } else {
      geometry
    }
    val safeGeometry = sanitizeGeometry(candidate.copy(y = maxOf(candidate.y, statusBarInsetTop())))
    if (activationState == PrivacyActivationState.SUSPENDED) {
      serviceHandler.postDelayed(reconcileWindowInsets, ORIENTATION_RETRY_MS)
      return
    }
    if (activationState == PrivacyActivationState.PAUSED) {
      if (orientationChanged || safeGeometry != geometry) {
        geometryOrientation = currentOrientation
        applyGeometry(safeGeometry, reason = "orientation-insets")
        persistGeometry()
      }
      val safeIconPosition = sanitizePausedIconPosition(topControlsParams.x, topControlsParams.y)
      if (safeIconPosition != Pair(topControlsParams.x, topControlsParams.y)) {
        topControlsParams.x = safeIconPosition.first
        topControlsParams.y = safeIconPosition.second
        topControlsView?.let { windowManager.updateViewLayout(it, topControlsParams) }
        persistPausedIconPosition()
      }
      return
    }
    if (!orientationChanged && safeGeometry == geometry) return
    when (activationState) {
      PrivacyActivationState.ACTIVE -> {
        surfaceView?.suspendPrivacyForMotion("orientation-insets")
        if (activationState != PrivacyActivationState.SUSPENDED) return
        geometryOrientation = currentOrientation
        applyGeometry(safeGeometry, reason = "orientation-insets")
        persistGeometry()
        surfaceView?.rearmPrivacyAfterMotion("orientation-insets")
      }
      PrivacyActivationState.STARTING -> {
        geometryOrientation = currentOrientation
        applyGeometry(safeGeometry, reason = "orientation-insets")
        persistGeometry()
      }
      PrivacyActivationState.INACTIVE,
      PrivacyActivationState.PAUSED,
      PrivacyActivationState.ERROR,
      PrivacyActivationState.BLOCKED -> Unit
      PrivacyActivationState.SUSPENDED -> Unit
    }
  }

  private fun availableOverlaySize(): Pair<Int, Int> {
    val metrics = windowManager.currentWindowMetrics
    val bounds = metrics.bounds
    val systemBarInsets = metrics.windowInsets.getInsetsIgnoringVisibility(
      WindowInsets.Type.systemBars(),
    )
    val navigationInsets = metrics.windowInsets.getInsetsIgnoringVisibility(
      WindowInsets.Type.navigationBars(),
    )
    val displayCutout = metrics.windowInsets.displayCutout
    val rightInset = maxOf(systemBarInsets.right, displayCutout?.safeInsetRight ?: 0)
    val bottomGuard = edgeInset() * BOTTOM_GUARD_EDGE_MULTIPLIER
    val available = OverlayGeometryPolicy.availableSize(
      boundsWidth = bounds.width(),
      boundsHeight = bounds.height(),
      systemInsetRight = rightInset,
      navigationInsetBottom = navigationInsets.bottom,
      bottomGuard = bottomGuard,
    )
    if (!loggedOverlayBounds) {
      loggedOverlayBounds = true
      Log.i(
        TAG,
        "Overlay boundary bounds=${bounds.width()}x${bounds.height()} " +
          "systemBars=$systemBarInsets navigationBars=$navigationInsets " +
          "bottomGuard=$bottomGuard available=${available.first}x${available.second}",
      )
    }
    return available
  }

  private fun overlayParams(
    width: Int,
    height: Int,
    x: Int,
    y: Int,
    touchable: Boolean,
    title: String,
    maximumObscuringOpacity: Float,
  ): WindowManager.LayoutParams {
    val windowState = OverlayWindowPolicy.surface(touchable, maximumObscuringOpacity)
    return WindowManager.LayoutParams(
      width,
      height,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      windowState.flags,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      this.x = x
      this.y = y
      this.title = title
      alpha = windowState.alpha
    }
  }

  private fun topControlsX(value: OverlayState.Geometry, compact: Boolean): Int {
    val groupX = value.x + (value.width - topControlsWidth(compact = false) - edgeInset()).coerceAtLeast(0)
    if (!compact) return groupX
    val pauseSlot = if (swapPauseAndStop) CONTROL_PADDING_DP + CONTROL_DP + CONTROL_GAP_DP else CONTROL_PADDING_DP
    return groupX + dp(pauseSlot)
  }
  private fun topControlsY(value: OverlayState.Geometry, compact: Boolean) =
    if (compact) {
      OverlayGeometryPolicy.compactTopAnchoredY(
        surfaceY = value.y,
        edgeInset = edgeInset(),
        controlPadding = dp(CONTROL_PADDING_DP),
        statusBarInsetTop = statusBarInsetTop(),
      )
    } else {
      value.y + edgeInset()
    }
  private fun topControlsWidth(compact: Boolean) = if (compact) dp(CONTROL_DP) else {
    dp(CONTROL_DP * 2 + CONTROL_GAP_DP + CONTROL_PADDING_DP * 2)
  }
  private fun topControlsHeight(compact: Boolean) = if (compact) dp(CONTROL_DP) else dp(CONTROLS_HEIGHT_DP)

  private fun bottomControlsX(value: OverlayState.Geometry) =
    value.x + (value.width - bottomControlsWidth()).coerceAtLeast(0)
  private fun bottomControlsY(value: OverlayState.Geometry) =
    OverlayGeometryPolicy.bottomAnchoredY(
      surfaceY = value.y,
      surfaceHeight = value.height,
      controlHeight = bottomControlsHeight(),
      statusBarInsetTop = statusBarInsetTop(),
    )
  private fun bottomControlsWidth() = dp(CONTROL_DP)
  private fun bottomControlsHeight() = dp(CONTROL_DP)
  private fun cornerHandleSize() = dp(CORNER_HANDLE_DIAMETER_DP)
  private fun cornerHandleX(value: OverlayState.Geometry, corner: ResizeCorner): Int {
    val centerX = when (corner) {
      ResizeCorner.TOP_LEFT, ResizeCorner.BOTTOM_LEFT -> value.x
      ResizeCorner.TOP_RIGHT, ResizeCorner.BOTTOM_RIGHT -> value.x + value.width
    }
    return centerX - cornerHandleSize() / 2
  }
  private fun cornerHandleY(value: OverlayState.Geometry, corner: ResizeCorner): Int {
    val centerY = when (corner) {
      ResizeCorner.TOP_LEFT, ResizeCorner.TOP_RIGHT -> value.y
      ResizeCorner.BOTTOM_LEFT, ResizeCorner.BOTTOM_RIGHT -> value.y + value.height
    }
    return centerY - cornerHandleSize() / 2
  }
  private fun isInsideCornerHandle(corner: ResizeCorner, rawX: Float, rawY: Float): Boolean {
    val radius = cornerHandleSize() / 2f
    val centerX = cornerHandleX(geometry, corner) + radius
    val centerY = cornerHandleY(geometry, corner) + radius
    val dx = rawX - centerX
    val dy = rawY - centerY
    return dx * dx + dy * dy <= radius * radius
  }
  private fun edgeInset() = dp(EDGE_INSET_DP).coerceAtLeast(2)
  private fun minimumOverlayX() = maxOf(
    edgeInset(),
    windowManager.currentWindowMetrics.windowInsets
      .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
      .left,
    windowManager.currentWindowMetrics.windowInsets.displayCutout?.safeInsetLeft ?: 0,
  )
  private fun statusBarInsetTop() = windowManager.currentWindowMetrics.windowInsets
    .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
    .top
  private fun currentOverlayOrientation(): Int {
    val bounds = windowManager.currentWindowMetrics.bounds
    return if (bounds.width() > bounds.height()) {
      Configuration.ORIENTATION_LANDSCAPE
    } else {
      Configuration.ORIENTATION_PORTRAIT
    }
  }
  private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

  private fun publishState(state: PrivacyActivationState) {
    activationState = state
    when (state) {
      PrivacyActivationState.ACTIVE -> {
        serviceHandler.removeCallbacks(activationTimeout)
        failureMessage = null
        OverlayState.clearLastError(this)
        getSystemService(NotificationManager::class.java).apply {
          cancel(ERROR_NOTIFICATION_ID)
          notify(NOTIFICATION_ID, ongoingNotification(state))
        }
      }
      PrivacyActivationState.STARTING,
      PrivacyActivationState.SUSPENDED,
      PrivacyActivationState.PAUSED -> getSystemService(NotificationManager::class.java)
        .notify(NOTIFICATION_ID, ongoingNotification(state))
      PrivacyActivationState.INACTIVE,
      PrivacyActivationState.ERROR,
      PrivacyActivationState.BLOCKED -> Unit
    }
    refreshTile()
  }

  private fun failClosed(message: String, error: Throwable? = null) {
    if (failureMessage != null) return
    serviceHandler.removeCallbacks(activationTimeout)
    if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
    val cleanupFailure = teardownOverlay()
    val userMessage = if (cleanupFailure == null) {
      activationState = PrivacyActivationState.ERROR
      message
    } else {
      Log.e(TAG, "Additional privacy cleanup failure", cleanupFailure)
      OverlayState.markCleanupBlocked(this)
      activationState = PrivacyActivationState.BLOCKED
      "$message Privacy cleanup also failed; restart the device before retrying."
    }
    failureMessage = userMessage
    OverlayState.setLastError(this, userMessage)
    showFailureNotification(userMessage)
    refreshTile()
    stopSelf()
  }

  private fun teardownOverlay(): Throwable? {
    if (teardownComplete) return null
    teardownComplete = true
    serviceHandler.removeCallbacks(activationTimeout)
    serviceHandler.removeCallbacks(reconcileWindowInsets)
    var firstFailure = surfaceView?.dispose()?.exceptionOrNull()
    if (::windowManager.isInitialized) {
      (listOfNotNull(topControlsView, bottomControlsView, surfaceView) + cornerHandleViews.values)
        .forEach { view ->
          if (view.isAttachedToWindow) {
            runCatching { windowManager.removeView(view) }
              .onFailure { if (firstFailure == null) firstFailure = it }
          }
        }
    }
    cornerHandleViews.clear()
    cornerHandleParams.clear()
    topControlsView = null
    bottomControlsView = null
    surfaceView = null
    return firstFailure
  }

  private fun ongoingNotification(state: PrivacyActivationState) = NotificationCompat.Builder(this, CHANNEL_ID)
    .setSmallIcon(if (state == PrivacyActivationState.ACTIVE) R.drawable.ic_privacy_tile_on else R.drawable.ic_privacy_tile_off)
    .setContentTitle(when (state) {
      PrivacyActivationState.ACTIVE -> "Privacy box is visible"
      PrivacyActivationState.SUSPENDED -> "Updating privacy box"
      PrivacyActivationState.PAUSED -> "Privacy box is paused"
      else -> "Starting privacy box"
    })
    .setContentText(when (state) {
      PrivacyActivationState.ACTIVE -> "Unlock to edit. Lock for touch-through."
      PrivacyActivationState.SUSPENDED -> "Privacy is temporarily inactive while the region moves or resizes."
      PrivacyActivationState.PAUSED -> "Tap the unlocked shield or Quick Settings tile to resume."
      else -> "Checking Samsung privacy capability before activation."
    })
    .setOngoing(true)
    .setContentIntent(mainActivityPendingIntent())
    .addAction(
      0,
      "Hide",
      PendingIntent.getService(
        this,
        2,
        Intent(this, PrivacyOverlayService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      ),
    )
    .build()

  private fun showFailureNotification(message: String) {
    getSystemService(NotificationManager::class.java).notify(
      ERROR_NOTIFICATION_ID,
      NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_privacy_tile_off)
        .setContentTitle("Privacy protection unavailable")
        .setContentText(message)
        .setContentIntent(mainActivityPendingIntent())
        .setAutoCancel(true)
        .build(),
    )
  }

  private fun mainActivityPendingIntent() = PendingIntent.getActivity(
    this,
    1,
    Intent(this, MainActivity::class.java),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
  )

  private fun createNotificationChannels() {
    getSystemService(NotificationManager::class.java).apply {
      createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Privacy box", NotificationManager.IMPORTANCE_LOW),
      )
      createNotificationChannel(
        NotificationChannel(ERROR_CHANNEL_ID, "Privacy box errors", NotificationManager.IMPORTANCE_DEFAULT),
      )
    }
  }

  private fun refreshTile() {
    TileService.requestListeningState(this, android.content.ComponentName(this, PrivacyTileService::class.java))
  }

  companion object {
    private const val TAG = "PrivacyBoxService"
    private const val CHANNEL_ID = "privacy_box"
    private const val ERROR_CHANNEL_ID = "privacy_box_errors"
    private const val NOTIFICATION_ID = 41
    private const val ERROR_NOTIFICATION_ID = 42
    private const val ACTION_STOP = "com.moni11811.privacybox.STOP"
    private const val ACTION_RESUME = "com.moni11811.privacybox.RESUME"
    private const val ACTION_REFRESH_CONTROLS = "com.moni11811.privacybox.REFRESH_CONTROLS"
    private const val ACTIVATION_TIMEOUT_MS = 2_000L
    private const val ORIENTATION_RETRY_MS = 120L
    private const val EDGE_INSET_DP = 2
    private const val MIN_WIDTH_DP = 132
    private const val MIN_HEIGHT_DP = 72
    private const val CONTROLS_HEIGHT_DP = 44
    private const val CONTROL_DP = 36
    private const val CONTROL_GAP_DP = 4
    private const val CONTROL_PADDING_DP = 4
    private const val CORNER_HANDLE_DIAMETER_DP = 72
    private const val BOTTOM_GUARD_EDGE_MULTIPLIER = 4

    @Volatile internal var activationState: PrivacyActivationState = PrivacyActivationState.INACTIVE
      private set

    val isRunning: Boolean
      get() = activationState == PrivacyActivationState.STARTING ||
        activationState == PrivacyActivationState.ACTIVE ||
        activationState == PrivacyActivationState.SUSPENDED ||
        activationState == PrivacyActivationState.PAUSED

    @Volatile internal var privacyApiFactory: () -> Result<PrivacyDisplayApi> =
      { ReflectiveSamsungPrivacyDisplayApi.resolve() }

    fun start(context: Context) {
      if (isRunning) return
      if (OverlayState.isCleanupBlocked(context)) {
        activationState = PrivacyActivationState.BLOCKED
        OverlayState.setLastError(
          context,
          "A previous privacy cleanup failed. Restart the device before retrying.",
        )
        TileService.requestListeningState(
          context,
          android.content.ComponentName(context, PrivacyTileService::class.java),
        )
        return
      }
      activationState = PrivacyActivationState.STARTING
      TileService.requestListeningState(
        context,
        android.content.ComponentName(context, PrivacyTileService::class.java),
      )
      runCatching {
        ContextCompat.startForegroundService(context, Intent(context, PrivacyOverlayService::class.java))
      }.onFailure {
        activationState = PrivacyActivationState.ERROR
        OverlayState.setLastError(context, "Android could not start the privacy service. Try again.")
        TileService.requestListeningState(
          context,
          android.content.ComponentName(context, PrivacyTileService::class.java),
        )
        Log.e(TAG, "Foreground service start failed", it)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, PrivacyOverlayService::class.java))
      if (
        activationState != PrivacyActivationState.ERROR &&
        activationState != PrivacyActivationState.BLOCKED
      ) {
        activationState = PrivacyActivationState.INACTIVE
      }
      TileService.requestListeningState(context, android.content.ComponentName(context, PrivacyTileService::class.java))
    }

    fun resume(context: Context) {
      if (activationState != PrivacyActivationState.PAUSED) {
        start(context)
        return
      }
      ContextCompat.startForegroundService(
        context,
        Intent(context, PrivacyOverlayService::class.java).setAction(ACTION_RESUME),
      )
    }

    fun refreshControlPreferences(context: Context) {
      if (!isRunning) return
      context.startService(
        Intent(context, PrivacyOverlayService::class.java).setAction(ACTION_REFRESH_CONTROLS),
      )
    }

    internal fun restorePrivacyApiFactoryForTests() {
      privacyApiFactory = { ReflectiveSamsungPrivacyDisplayApi.resolve() }
    }

    internal fun resetStateAfterInstrumentedTest(context: Context) {
      restorePrivacyApiFactoryForTests()
      activationState = PrivacyActivationState.INACTIVE
      OverlayState.clearLastError(context)
      context.getSystemService(NotificationManager::class.java).cancel(ERROR_NOTIFICATION_ID)
      TileService.requestListeningState(
        context,
        android.content.ComponentName(context, PrivacyTileService::class.java),
      )
    }
  }
}

private class PrivacySurfaceView(
  context: Context,
  privacyApi: PrivacyDisplayApi,
  initiallyLocked: Boolean,
  initiallyShowLockIcon: Boolean,
  private val onDragStart: () -> Unit,
  private val onDrag: (Int, Int) -> Unit,
  private val onDragEnd: () -> Unit,
  private val onPrivacyActivated: () -> Unit,
  private val onPrivacySuspended: () -> Unit,
  private val onPrivacyPaused: () -> Unit,
  private val onPrivacyFailure: (String, Throwable) -> Unit,
) : FrameLayout(context) {
  private val density = resources.displayMetrics.density
  private val privacyInset = (2f * density).roundToInt().coerceAtLeast(2)
  private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.rgb(64, 225, 255)
    style = Paint.Style.STROKE
    strokeWidth = 4f * density
  }
  private val cornerPaint = Paint(borderPaint)
  private val cornerRadius = 14f * density
  private val privacyMarker = View(context).apply {
    setBackgroundColor(Color.TRANSPARENT)
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }
  private val privacySession = PrivacyDisplaySession(privacyApi, privacyMarker)
  private var refreshScheduled = false
  private var pendingReason = "update"
  private var privacySuspendedForMotion = false
  private var locked = initiallyLocked
  private var showLockIcon = initiallyShowLockIcon
  private var downX = 0f
  private var downY = 0f
  private var activeResizeCorner: ResizeCorner? = null
  private var lockPressActive = false
  private val refreshPrivacy = Runnable {
    refreshScheduled = false
    refreshPrivacyRegion(pendingReason)
  }
  private val rearmPrivacy = Runnable {
    privacySuspendedForMotion = false
    refreshPrivacyRegion(pendingReason)
  }

  init {
    setWillNotDraw(false)
    setLayerType(LAYER_TYPE_HARDWARE, null)
    addView(
      privacyMarker,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
        setMargins(privacyInset, privacyInset, privacyInset, privacyInset)
      },
    )
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val inset = (borderPaint.strokeWidth + CORNER_EXTRA_DP * density) / 2f
    val bounds = RectF(inset, inset, width - inset, height - inset)
    if (locked) {
      if (!showLockIcon) {
        ResizeCorner.entries.forEach { corner ->
          drawResizeCorner(canvas, bounds, corner, forceActive = true)
        }
      } else if (lockPressActive) {
        drawResizeCorner(canvas, bounds, ResizeCorner.BOTTOM_RIGHT, forceActive = true)
      }
      return
    }
    canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
    ResizeCorner.entries.forEach { corner ->
      drawResizeCorner(
        canvas,
        bounds,
        corner,
        forceActive = lockPressActive && corner == ResizeCorner.BOTTOM_RIGHT,
      )
    }
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    schedulePrivacyRefresh("resize")
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (locked) return false
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = event.rawX
        downY = event.rawY
        onDragStart()
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        val dx = (event.rawX - downX).roundToInt()
        val dy = (event.rawY - downY).roundToInt()
        onDrag(dx, dy)
        return true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        onDragEnd()
        performClick()
        return true
      }
    }
    return true
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  private fun drawResizeCorner(
    canvas: Canvas,
    bounds: RectF,
    corner: ResizeCorner,
    forceActive: Boolean = false,
  ) {
    val active = forceActive || activeResizeCorner == corner
    cornerPaint.color = if (active) ACTIVE_CORNER_COLOR else borderPaint.color
    cornerPaint.strokeWidth = borderPaint.strokeWidth + CORNER_EXTRA_DP * density
    val diameter = cornerRadius * 2f
    val arcBounds: RectF
    val startAngle: Float
    when (corner) {
      ResizeCorner.TOP_LEFT -> {
        arcBounds = RectF(bounds.left, bounds.top, bounds.left + diameter, bounds.top + diameter)
        startAngle = 180f
      }
      ResizeCorner.TOP_RIGHT -> {
        arcBounds = RectF(bounds.right - diameter, bounds.top, bounds.right, bounds.top + diameter)
        startAngle = 270f
      }
      ResizeCorner.BOTTOM_RIGHT -> {
        arcBounds = RectF(bounds.right - diameter, bounds.bottom - diameter, bounds.right, bounds.bottom)
        startAngle = 0f
      }
      ResizeCorner.BOTTOM_LEFT -> {
        arcBounds = RectF(bounds.left, bounds.bottom - diameter, bounds.left + diameter, bounds.bottom)
        startAngle = 90f
      }
    }
    canvas.drawArc(arcBounds, startAngle, 90f, false, cornerPaint)
  }

  fun setLocked(value: Boolean) {
    locked = value
    activeResizeCorner = null
    invalidate()
  }

  fun setActiveResizeCorner(corner: ResizeCorner?) {
    activeResizeCorner = corner
    invalidate()
  }

  fun setLockPressActive(active: Boolean) {
    lockPressActive = active
    invalidate()
  }

  fun setShowLockIcon(show: Boolean) {
    showLockIcon = show
    invalidate()
  }

  fun schedulePrivacyRefresh(reason: String) {
    pendingReason = reason
    if (privacySuspendedForMotion) return
    if (refreshScheduled) return
    refreshScheduled = true
    postOnAnimation(refreshPrivacy)
  }

  private fun refreshPrivacyRegion(reason: String) {
    if (privacySuspendedForMotion) return
    if (width <= privacyInset * 2 || height <= privacyInset * 2) return
    privacySession.activate(
      PrivacyDisplayRegion(
        cornerRadius = 12f * density,
        left = privacyInset,
        top = privacyInset,
        right = width - privacyInset,
        bottom = height - privacyInset,
      ),
    ).onSuccess {
      Log.i(TAG, "Privacy region refreshed reason=$reason width=$width height=$height")
      onPrivacyActivated()
    }.onFailure {
      Log.e(TAG, "Privacy region API failed reason=$reason", it)
      onPrivacyFailure("activation", it)
    }
  }

  fun suspendPrivacyForMotion(reason: String) {
    removeCallbacks(refreshPrivacy)
    removeCallbacks(rearmPrivacy)
    if (privacySuspendedForMotion) return
    privacySession.suspendForMotion()
      .onSuccess {
        privacySuspendedForMotion = true
        onPrivacySuspended()
        Log.i(TAG, "Privacy region disabled reason=$reason")
      }
      .onFailure {
        Log.e(TAG, "Privacy region disable failed reason=$reason", it)
        onPrivacyFailure("disable", it)
      }
  }

  fun rearmPrivacyAfterMotion(reason: String) {
    if (!privacySuspendedForMotion) return
    removeCallbacks(rearmPrivacy)
    pendingReason = "$reason-settled"
    postDelayed(rearmPrivacy, PRIVACY_SETTLE_MS)
  }

  fun pausePrivacy() {
    removeCallbacks(refreshPrivacy)
    removeCallbacks(rearmPrivacy)
    if (privacySuspendedForMotion) return
    privacySession.suspendForMotion()
      .onSuccess {
        privacySuspendedForMotion = true
        onPrivacyPaused()
        Log.i(TAG, "Privacy region paused")
      }
      .onFailure {
        Log.e(TAG, "Privacy region pause failed", it)
        onPrivacyFailure("pause", it)
      }
  }

  fun resumePrivacy() {
    if (!privacySuspendedForMotion) return
    removeCallbacks(rearmPrivacy)
    privacySuspendedForMotion = false
    pendingReason = "pause-resume"
    refreshPrivacyRegion(pendingReason)
  }

  fun dispose(): Result<Unit> {
    removeCallbacks(refreshPrivacy)
    removeCallbacks(rearmPrivacy)
    refreshScheduled = false
    privacySuspendedForMotion = false
    return privacySession.dispose()
  }

  companion object {
    private const val TAG = "PrivacyBoxSurface"
    private const val PRIVACY_SETTLE_MS = 90L
    private const val CORNER_EXTRA_DP = 3f
    private val ACTIVE_CORNER_COLOR = Color.rgb(151, 78, 8)
  }
}

private class PrivacyTopControlsView(
  context: Context,
  initiallySwapped: Boolean,
  initiallyLockedPauseOnly: Boolean,
  private val onPause: () -> Unit,
  private val onStop: () -> Unit,
  private val onPausedDragStart: () -> Unit,
  private val onPausedDrag: (Int, Int) -> Unit,
  private val onPausedDragEnd: () -> Unit,
  private val isTopRightResizeTouch: (Float, Float) -> Boolean,
  private val onTopRightResizeStart: () -> Unit,
  private val onTopRightResize: (Int, Int) -> Unit,
  private val onTopRightResizeEnd: () -> Unit,
) : View(context) {
  private enum class Control { PAUSE, STOP, NONE }

  private val density = resources.displayMetrics.density
  private val controlSize = 36f * density
  private val gap = 4f * density
  private val padding = 4f * density
  private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
  private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 2.2f * density
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private var paused = false
  private var swapped = initiallySwapped
  private var lockedPauseOnly = initiallyLockedPauseOnly
  private var activeControl = Control.NONE
  private var pausedDragging = false
  private var pausedDownRawX = 0f
  private var pausedDownRawY = 0f
  private var topRightResizeCandidate = false
  private var topRightResizing = false
  private var controlDownRawX = 0f
  private var controlDownRawY = 0f

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (paused) {
      drawUnlockedShield(canvas)
      return
    }
    drawPause(canvas)
    if (!lockedPauseOnly) drawStop(canvas)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        activeControl = hit(event.x, event.y)
        controlDownRawX = event.rawX
        controlDownRawY = event.rawY
        topRightResizeCandidate = !paused && !lockedPauseOnly &&
          isTopRightResizeTouch(event.rawX, event.rawY)
        topRightResizing = false
        if (paused && activeControl == Control.PAUSE) {
          pausedDownRawX = event.rawX
          pausedDownRawY = event.rawY
          pausedDragging = false
          onPausedDragStart()
        }
        invalidate()
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (paused && activeControl == Control.PAUSE) {
          val dx = (event.rawX - pausedDownRawX).roundToInt()
          val dy = (event.rawY - pausedDownRawY).roundToInt()
          if (!pausedDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
            pausedDragging = true
          }
          if (pausedDragging) onPausedDrag(dx, dy)
        } else if (topRightResizeCandidate) {
          val dx = (event.rawX - controlDownRawX).roundToInt()
          val dy = (event.rawY - controlDownRawY).roundToInt()
          if (!topRightResizing && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
            topRightResizing = true
            activeControl = Control.NONE
            onTopRightResizeStart()
          }
          if (topRightResizing) onTopRightResize(dx, dy)
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        if (topRightResizing) {
          onTopRightResizeEnd()
        } else if (paused && activeControl == Control.PAUSE) {
          if (pausedDragging) onPausedDragEnd() else onPause()
        } else {
          when (activeControl) {
            Control.PAUSE -> if (pauseRect().contains(event.x, event.y)) onPause()
            Control.STOP -> if (stopRect().contains(event.x, event.y)) onStop()
            Control.NONE -> Unit
          }
        }
        activeControl = Control.NONE
        pausedDragging = false
        topRightResizeCandidate = false
        topRightResizing = false
        invalidate()
        performClick()
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        if (paused && pausedDragging) onPausedDragEnd()
        if (topRightResizing) onTopRightResizeEnd()
        activeControl = Control.NONE
        pausedDragging = false
        topRightResizeCandidate = false
        topRightResizing = false
        invalidate()
        return true
      }
    }
    return true
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  fun setPaused(value: Boolean) {
    paused = value
    activeControl = Control.NONE
    pausedDragging = false
    topRightResizeCandidate = false
    topRightResizing = false
    invalidate()
  }

  fun setSwapped(value: Boolean) {
    swapped = value
    activeControl = Control.NONE
    invalidate()
  }

  fun setLockedPauseOnly(value: Boolean) {
    lockedPauseOnly = value
    activeControl = Control.NONE
    topRightResizeCandidate = false
    topRightResizing = false
    invalidate()
  }

  private fun hit(x: Float, y: Float): Control = when {
    pauseRect().contains(x, y) -> Control.PAUSE
    !paused && !lockedPauseOnly && stopRect().contains(x, y) -> Control.STOP
    else -> Control.NONE
  }

  private fun pauseRect() = if (paused || lockedPauseOnly) {
    RectF(0f, 0f, controlSize, controlSize)
  } else if (swapped) {
    secondControlRect()
  } else {
    firstControlRect()
  }

  private fun stopRect() = if (swapped) firstControlRect() else secondControlRect()

  private fun firstControlRect() = RectF(
    padding,
    padding,
    padding + controlSize,
    padding + controlSize,
  )

  private fun secondControlRect() = RectF(
    padding + controlSize + gap,
    padding,
    padding + controlSize * 2 + gap,
    padding + controlSize,
  )

  private fun drawPause(canvas: Canvas) {
    iconPaint.color = if (lockedPauseOnly && activeControl != Control.PAUSE) {
      Color.argb(LOCKED_IDLE_ALPHA, 170, 241, 255)
    } else {
      Color.rgb(170, 241, 255)
    }
    val rect = pauseRect()
    val offset = 5f * density
    val top = rect.centerY() - 9f * density
    val bottom = rect.centerY() + 9f * density
    canvas.drawLine(rect.centerX() - offset, top, rect.centerX() - offset, bottom, iconPaint)
    canvas.drawLine(rect.centerX() + offset, top, rect.centerX() + offset, bottom, iconPaint)
  }

  private fun drawStop(canvas: Canvas) {
    iconPaint.color = Color.rgb(255, 151, 151)
    val rect = stopRect()
    val side = 14f * density
    canvas.drawRect(
      rect.centerX() - side / 2,
      rect.centerY() - side / 2,
      rect.centerX() + side / 2,
      rect.centerY() + side / 2,
      iconPaint,
    )
  }

  private fun drawUnlockedShield(canvas: Canvas) {
    iconPaint.color = Color.rgb(170, 241, 255)
    val rect = pauseRect()
    val cx = rect.centerX()
    val path = Path().apply {
      moveTo(cx, rect.top + 4f * density)
      lineTo(rect.right - 7f * density, rect.top + 9f * density)
      lineTo(rect.right - 7f * density, rect.centerY() + 2f * density)
      cubicTo(
        rect.right - 7f * density,
        rect.bottom - 7f * density,
        cx,
        rect.bottom - 3f * density,
        cx,
        rect.bottom - 3f * density,
      )
      cubicTo(
        cx,
        rect.bottom - 3f * density,
        rect.left + 7f * density,
        rect.bottom - 7f * density,
        rect.left + 7f * density,
        rect.centerY() + 2f * density,
      )
      lineTo(rect.left + 7f * density, rect.top + 9f * density)
      close()
    }
    canvas.drawPath(path, iconPaint)
    val body = RectF(cx - 5f * density, rect.centerY(), cx + 5f * density, rect.centerY() + 8f * density)
    canvas.drawRoundRect(body, 2f * density, 2f * density, iconPaint)
    val shackle = RectF(cx - 5f * density, rect.centerY() - 8f * density, cx + 5f * density, rect.centerY() + 3f * density)
    canvas.drawArc(shackle, 190f, 105f, false, iconPaint)
  }

  companion object {
    private const val LOCKED_IDLE_ALPHA = 10
  }
}

private class PrivacyCornerHandleView(
  context: Context,
  private val corner: ResizeCorner,
  private val onPressedChanged: (Boolean) -> Unit,
  private val onResizeStart: () -> Unit,
  private val onResize: (Int, Int) -> Unit,
  private val onResizeEnd: () -> Unit,
  private val onLockToggle: () -> Unit,
) : View(context) {
  private val density = resources.displayMetrics.density
  private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
  private val gestureSlop = (touchSlop * 1.5f).roundToInt()
  private val handler = Handler(Looper.getMainLooper())
  private var downRawX = 0f
  private var downRawY = 0f
  private var tracking = false
  private var dragging = false
  private var locked = false
  private var cornerLockingEnabled = true
  private val longPressLock = Runnable {
    if (!tracking || dragging || !cornerLockingEnabled) return@Runnable
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    onLockToggle()
  }

  init {
    contentDescription = "${corner.name.lowercase().replace('_', ' ')} resize handle"
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        if (!isInsideHitArea(event.x, event.y)) return false
        downRawX = event.rawX
        downRawY = event.rawY
        tracking = true
        dragging = false
        onPressedChanged(true)
        if (cornerLockingEnabled) handler.postDelayed(longPressLock, LOCK_HOLD_MS)
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (!tracking) return false
        val dx = (event.rawX - downRawX).roundToInt()
        val dy = (event.rawY - downRawY).roundToInt()
        if (locked) {
          if (abs(dx) > gestureSlop || abs(dy) > gestureSlop) handler.removeCallbacks(longPressLock)
          return true
        }
        if (!dragging && (abs(dx) > gestureSlop || abs(dy) > gestureSlop)) {
          handler.removeCallbacks(longPressLock)
          dragging = true
          onResizeStart()
        }
        if (dragging) onResize(dx, dy)
        return true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (!tracking) return false
        handler.removeCallbacks(longPressLock)
        if (dragging) onResizeEnd()
        tracking = false
        dragging = false
        onPressedChanged(false)
        performClick()
        return true
      }
    }
    return false
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  fun setInteractionState(locked: Boolean, cornerLockingEnabled: Boolean) {
    this.locked = locked
    this.cornerLockingEnabled = cornerLockingEnabled
    if (!cornerLockingEnabled) handler.removeCallbacks(longPressLock)
  }

  private fun isInsideHitArea(x: Float, y: Float): Boolean {
    val outerX = width / 2f
    val outerY = height / 2f
    if (!locked) {
      val dx = x - outerX
      val dy = y - outerY
      val radius = minOf(width, height) / 2f
      return dx * dx + dy * dy <= radius * radius
    }
    if (!cornerLockingEnabled) return false

    val arcRadius = CORNER_RADIUS_DP * density
    val arcInset = CORNER_INSET_DP * density
    val offset = arcRadius + arcInset
    val arcCenterX = when (corner) {
      ResizeCorner.TOP_LEFT, ResizeCorner.BOTTOM_LEFT -> outerX + offset
      ResizeCorner.TOP_RIGHT, ResizeCorner.BOTTOM_RIGHT -> outerX - offset
    }
    val arcCenterY = when (corner) {
      ResizeCorner.TOP_LEFT, ResizeCorner.TOP_RIGHT -> outerY + offset
      ResizeCorner.BOTTOM_LEFT, ResizeCorner.BOTTOM_RIGHT -> outerY - offset
    }
    val correctQuadrant = when (corner) {
      ResizeCorner.TOP_LEFT -> x <= arcCenterX && y <= arcCenterY
      ResizeCorner.TOP_RIGHT -> x >= arcCenterX && y <= arcCenterY
      ResizeCorner.BOTTOM_LEFT -> x <= arcCenterX && y >= arcCenterY
      ResizeCorner.BOTTOM_RIGHT -> x >= arcCenterX && y >= arcCenterY
    }
    if (!correctQuadrant) return false
    val dx = x - arcCenterX
    val dy = y - arcCenterY
    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
    return abs(distance - arcRadius) <= LOCKED_LINE_TOUCH_DP * density / 2f
  }

  companion object {
    private const val LOCK_HOLD_MS = 650L
    private const val CORNER_RADIUS_DP = 14f
    private const val CORNER_INSET_DP = 3.5f
    private const val LOCKED_LINE_TOUCH_DP = 18f
  }
}

private class PrivacyBottomControlsView(
  context: Context,
  initiallyLocked: Boolean,
  initiallyShowIcon: Boolean,
  private val onPressedChanged: (Boolean) -> Unit,
  private val onLockChanged: (Boolean) -> Unit,
) : View(context) {
  private enum class Control { LOCK, NONE }

  private val density = resources.displayMetrics.density
  private val controlSize = 36f * density
  private val handler = Handler(Looper.getMainLooper())
  private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 2.2f * density
    strokeCap = Paint.Cap.ROUND
  }
  private var locked = initiallyLocked
  private var showIcon = initiallyShowIcon
  private var activeControl = Control.NONE

  init {
    contentDescription = if (locked) "Hold to unlock privacy box" else "Hold to lock privacy box"
  }

  private val longPressLock = Runnable {
    if (activeControl != Control.LOCK) return@Runnable
    locked = !locked
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    onLockChanged(locked)
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (showIcon) drawLock(canvas)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        activeControl = hit(event.x, event.y)
        if (activeControl == Control.LOCK) {
          onPressedChanged(true)
          handler.postDelayed(longPressLock, LOCK_HOLD_MS)
        }
        invalidate()
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (activeControl == Control.LOCK && !lockRect().contains(event.x, event.y)) {
          handler.removeCallbacks(longPressLock)
          activeControl = Control.NONE
          onPressedChanged(false)
          invalidate()
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        handler.removeCallbacks(longPressLock)
        onPressedChanged(false)
        activeControl = Control.NONE
        invalidate()
        performClick()
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        handler.removeCallbacks(longPressLock)
        onPressedChanged(false)
        activeControl = Control.NONE
        invalidate()
        return true
      }
    }
    return true
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  fun setLocked(value: Boolean) {
    locked = value
    contentDescription = if (locked) "Hold to unlock privacy box" else "Hold to lock privacy box"
    invalidate()
  }

  fun setShowIcon(show: Boolean) {
    showIcon = show
    invalidate()
  }

  private fun hit(x: Float, y: Float): Control = when {
    lockRect().contains(x, y) -> Control.LOCK
    else -> Control.NONE
  }

  private fun lockRect() = RectF(0f, 0f, controlSize, controlSize)

  private fun drawLock(canvas: Canvas) {
    iconPaint.color = when {
      locked && activeControl != Control.LOCK -> Color.argb(LOCKED_IDLE_ALPHA, 255, 183, 65)
      locked -> Color.rgb(255, 183, 65)
      else -> Color.rgb(170, 241, 255)
    }
    val rect = lockRect()
    val centerX = rect.centerX() - 4f * density
    val centerY = rect.centerY() - 4f * density
    val body = RectF(
      centerX - 8f * density,
      centerY - 1f * density,
      centerX + 8f * density,
      centerY + 11f * density,
    )
    canvas.drawRoundRect(body, 3f * density, 3f * density, iconPaint)
    val shackle = RectF(
      centerX - 7f * density,
      centerY - 12f * density,
      centerX + 7f * density,
      centerY + 6f * density,
    )
    canvas.drawArc(shackle, 190f, if (locked) 160f else 105f, false, iconPaint)
  }

  companion object {
    private const val LOCK_HOLD_MS = 650L
    private const val LOCKED_IDLE_ALPHA = 10
  }
}
