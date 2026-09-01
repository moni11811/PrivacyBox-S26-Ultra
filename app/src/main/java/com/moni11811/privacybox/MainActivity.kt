package com.moni11811.privacybox

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
  private lateinit var statusTitle: TextView
  private lateinit var statusDetail: TextView
  private lateinit var primaryAction: Button
  private val statusRefresh = object : Runnable {
    override fun run() {
      updateStatus()
      window.decorView.postDelayed(this, STATUS_REFRESH_MS)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), dp(26), dp(20), dp(30))
      setBackgroundColor(BACKGROUND)
    }

    content.addView(text("Privacy Box", 30f, Color.WHITE, bold = true))
    content.addView(text("A flexible Samsung privacy region for any app.", 15f, MUTED).apply {
      setPadding(0, dp(4), 0, dp(22))
    })

    val statusCard = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(14), dp(16), dp(14))
      background = rounded(STATUS_SURFACE, 18)
    }
    statusTitle = text("Checking…", 18f, Color.WHITE, bold = true)
    statusDetail = text("", 13f, MUTED).apply { setPadding(0, dp(4), 0, 0) }
    statusCard.addView(statusTitle)
    statusCard.addView(statusDetail)
    content.addView(statusCard, matchWrap().apply { bottomMargin = dp(14) })

    primaryAction = styledButton("Show privacy box", primary = true) {
      if (PrivacyOverlayService.activationState == PrivacyActivationState.PAUSED) {
        PrivacyOverlayService.resume(this)
        window.decorView.postDelayed({ updateStatus() }, 250)
      } else if (PrivacyOverlayService.isRunning) {
        PrivacyOverlayService.stop(this)
        window.decorView.postDelayed({ updateStatus() }, 250)
      } else {
        ensurePermissionAndStart()
      }
    }
    content.addView(primaryAction)

    content.addView(sectionTitle("Quick access"))
    content.addView(actionRow(
      styledButton("Add tile", compact = true) { requestTile() },
      styledButton("Reset box", compact = true) { resetGeometry() },
    ))

    content.addView(sectionTitle("On-screen controls"))
    content.addView(featureRow("Move", "Drag anywhere inside the unlocked box."))
    content.addView(featureRow("Resize", "Drag the invisible circle centered across any corner."))
    content.addView(featureRow("Pause", "Tap to collapse into a shield, then drag the shield anywhere."))
    content.addView(featureRow("Lock", "Hold any corner, or enable the optional lock icon."))
    content.addView(featureRow("Stop", "Tap the top-right square or use the Quick Settings tile."))

    content.addView(sectionTitle("Control options"))
    content.addView(optionRow(
      title = "Swap Pause and Stop",
      detail = "Reverses their top-right order.",
      checked = OverlayState.isPauseStopSwapped(this),
    ) { swapped ->
      OverlayState.setPauseStopSwapped(this, swapped)
      PrivacyOverlayService.refreshControlPreferences(this)
    })
    content.addView(optionRow(
      title = "Keep Pause when locked",
      detail = "Leaves Pause at 4% opacity while the box is locked.",
      checked = OverlayState.keepPauseVisibleWhenLocked(this),
    ) { keepVisible ->
      OverlayState.setKeepPauseVisibleWhenLocked(this, keepVisible)
      PrivacyOverlayService.refreshControlPreferences(this)
    })
    content.addView(optionRow(
      title = "Show lock icon",
      detail = "Uses the lock icon instead of corner hold-locking.",
      checked = OverlayState.showLockIcon(this),
    ) { show ->
      OverlayState.setShowLockIcon(this, show)
      PrivacyOverlayService.refreshControlPreferences(this)
    })

    content.addView(TextView(this).apply {
      text = "The cyan outline is intentionally visible from the front. The enclosed screen area is the Samsung partial privacy region."
      textSize = 12f
      setTextColor(Color.rgb(131, 151, 174))
      setPadding(0, dp(20), 0, 0)
    })

    setContentView(ScrollView(this).apply {
      isFillViewport = true
      setBackgroundColor(BACKGROUND)
      addView(content)
    })
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
  }

  override fun onResume() {
    super.onResume()
    val permission = Settings.canDrawOverlays(this)
    when (OverlayStartAuthorization.onResume(this, permission)) {
      OverlayPermissionAction.OPEN_SETTINGS -> launchOverlayPermissionSettings()
      OverlayPermissionAction.START_OVERLAY -> PrivacyOverlayService.start(this)
      OverlayPermissionAction.NONE -> Unit
    }
    window.decorView.removeCallbacks(statusRefresh)
    statusRefresh.run()
  }

  override fun onPause() {
    window.decorView.removeCallbacks(statusRefresh)
    super.onPause()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(Intent(this, MainActivity::class.java))
  }

  private fun ensurePermissionAndStart() {
    if (Settings.canDrawOverlays(this)) {
      PrivacyOverlayService.start(this)
      window.decorView.postDelayed({ updateStatus() }, 250)
      return
    }
    if (!OverlayStartAuthorization.requestFromApp(this)) {
      OverlayState.setLastError(this, "Unable to prepare the permission request. Try again.")
      updateStatus()
      return
    }
    launchOverlayPermissionSettings()
  }

  private fun launchOverlayPermissionSettings() {
    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
  }

  private fun requestTile() {
    getSystemService(StatusBarManager::class.java).requestAddTileService(
      ComponentName(this, PrivacyTileService::class.java),
      "Privacy box",
      Icon.createWithResource(this, R.drawable.ic_privacy_tile_off),
      mainExecutor,
    ) { result ->
      runOnUiThread {
        statusDetail.text = when (result) {
          StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Quick Settings tile added. Swipe down to toggle the box."
          StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Quick Settings tile is already installed."
          else -> "Quick Settings returned result $result."
        }
      }
    }
  }

  private fun resetGeometry() {
    val wasRunning = PrivacyOverlayService.isRunning
    OverlayState.reset(this)
    if (wasRunning) {
      PrivacyOverlayService.stop(this)
      window.decorView.postDelayed({
        if (PrivacyOverlayService.activationState == PrivacyActivationState.INACTIVE) {
          PrivacyOverlayService.start(this)
        }
        updateStatus()
      }, 350)
    } else {
      updateStatus()
    }
  }

  private fun updateStatus() {
    val permission = Settings.canDrawOverlays(this)
    val state = PrivacyOverlayService.activationState
    val running = PrivacyOverlayService.isRunning
    val locked = OverlayState.isLocked(this)
    val lastError = OverlayState.lastError(this)
    val cleanupBlocked = OverlayState.isCleanupBlocked(this)
    statusTitle.text = when {
      !permission -> "Permission required"
      state == PrivacyActivationState.ACTIVE -> "Privacy box is visible"
      state == PrivacyActivationState.STARTING -> "Starting privacy protection"
      state == PrivacyActivationState.SUSPENDED -> "Updating privacy protection"
      state == PrivacyActivationState.PAUSED -> "Privacy box paused"
      cleanupBlocked || state == PrivacyActivationState.BLOCKED -> "Device restart required"
      state == PrivacyActivationState.ERROR || lastError != null -> "Privacy unavailable"
      else -> "Ready"
    }
    statusTitle.setTextColor(when {
      permission && state == PrivacyActivationState.ACTIVE -> ACCENT
      state == PrivacyActivationState.ERROR || cleanupBlocked || state == PrivacyActivationState.BLOCKED || lastError != null -> ERROR
      else -> Color.WHITE
    })
    statusDetail.text = when {
      !permission -> "Grant “appear on top” once. The Quick Settings tile works after that."
      state == PrivacyActivationState.ACTIVE -> "Privacy active · ${if (locked) "movement locked" else "move and resize enabled"}"
      state == PrivacyActivationState.STARTING -> "Checking Samsung privacy capability and activating the region."
      state == PrivacyActivationState.SUSPENDED -> "The privacy region is updating; wait for confirmation."
      state == PrivacyActivationState.PAUSED -> "Privacy is off · tap Resume or the unlocked shield."
      cleanupBlocked || state == PrivacyActivationState.BLOCKED -> lastError ?: "Restart the device before retrying privacy mode."
      state == PrivacyActivationState.ERROR || lastError != null -> lastError ?: "Privacy activation failed. Try again."
      else -> "Overlay permission granted · tap Show or use the Quick Settings tile."
    }
    primaryAction.text = when {
      state == PrivacyActivationState.PAUSED -> "Resume privacy box"
      running -> "Hide privacy box"
      cleanupBlocked || state == PrivacyActivationState.BLOCKED -> "Restart device to retry"
      lastError != null -> "Retry privacy box"
      permission -> "Show privacy box"
      else -> "Grant permission"
    }
    primaryAction.isEnabled = !cleanupBlocked && state != PrivacyActivationState.BLOCKED
  }

  private fun sectionTitle(label: String) = text(label, 14f, MUTED, bold = true).apply {
    setPadding(0, dp(22), 0, dp(9))
  }

  private fun featureRow(title: String, detail: String): View {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.TOP
      setPadding(dp(13), dp(11), dp(13), dp(11))
      background = rounded(SURFACE, 14)
    }
    row.addView(text(title, 14f, Color.WHITE, bold = true), LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT))
    row.addView(text(detail, 13f, MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    return row.apply { layoutParams = matchWrap().apply { bottomMargin = dp(7) } }
  }

  private fun optionRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
  ): View {
    val copy = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      addView(text(title, 14f, Color.WHITE, bold = true))
      addView(text(detail, 12f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
    }
    val toggle = Switch(this).apply {
      isChecked = checked
      contentDescription = title
      setOnCheckedChangeListener { _, value -> onChanged(value) }
    }
    return LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(13), dp(10), dp(10), dp(10))
      background = rounded(SURFACE, 14)
      addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      layoutParams = matchWrap().apply { bottomMargin = dp(7) }
    }
  }

  private fun actionRow(vararg buttons: Button) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    buttons.forEachIndexed { index, button ->
      addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
        if (index > 0) leftMargin = dp(8)
      })
    }
  }

  private fun styledButton(
    label: String,
    primary: Boolean = false,
    compact: Boolean = false,
    action: () -> Unit,
  ) = Button(this).apply {
    text = label
    isAllCaps = false
    textSize = if (compact) 14f else 16f
    setTextColor(if (primary) Color.rgb(3, 25, 34) else Color.WHITE)
    background = rounded(if (primary) ACCENT else BUTTON_SURFACE, 15)
    setOnClickListener { action() }
    if (!compact) layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
  }

  private fun text(label: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = label
    textSize = size
    setTextColor(color)
    if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
  }

  private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
  }

  private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
  private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

  companion object {
    private const val BACKGROUND = 0xFF070B15.toInt()
    private const val SURFACE = 0xFF111A28.toInt()
    private const val STATUS_SURFACE = 0xFF0E1B24.toInt()
    private const val BUTTON_SURFACE = 0xFF1A2939.toInt()
    private const val ACCENT = 0xFF48DCF6.toInt()
    private const val ERROR = 0xFFFF8A8A.toInt()
    private const val MUTED = 0xFFB8C4D5.toInt()
    private const val STATUS_REFRESH_MS = 250L
  }
}
