package com.moni11811.privacybox

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings

object OverlayState {
  private const val PREFS = "privacy_overlay"
  private const val KEY_X = "x"
  private const val KEY_Y = "y"
  private const val KEY_WIDTH = "width"
  private const val KEY_HEIGHT = "height"
  private const val KEY_LOCKED = "locked"
  private const val KEY_SWAP_PAUSE_STOP = "swap_pause_stop"
  private const val KEY_KEEP_PAUSE_WHEN_LOCKED = "keep_pause_when_locked"
  private const val KEY_SHOW_LOCK_ICON = "show_lock_icon"
  private const val KEY_PAUSED_ICON_X = "paused_icon_x"
  private const val KEY_PAUSED_ICON_Y = "paused_icon_y"
  private const val KEY_LAST_ERROR = "last_error"
  private const val KEY_CLEANUP_BLOCKED_BOOT_COUNT = "cleanup_blocked_boot_count"

  data class Geometry(val x: Int, val y: Int, val width: Int, val height: Int)

  fun isLocked(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCKED, false)

  fun setLocked(context: Context, locked: Boolean) {
    prefs(context).edit().putBoolean(KEY_LOCKED, locked).apply()
  }

  fun isPauseStopSwapped(context: Context): Boolean =
    prefs(context).getBoolean(KEY_SWAP_PAUSE_STOP, false)

  fun setPauseStopSwapped(context: Context, swapped: Boolean) {
    prefs(context).edit().putBoolean(KEY_SWAP_PAUSE_STOP, swapped).apply()
  }

  fun keepPauseVisibleWhenLocked(context: Context): Boolean =
    prefs(context).getBoolean(KEY_KEEP_PAUSE_WHEN_LOCKED, false)

  fun setKeepPauseVisibleWhenLocked(context: Context, keepVisible: Boolean) {
    prefs(context).edit().putBoolean(KEY_KEEP_PAUSE_WHEN_LOCKED, keepVisible).apply()
  }

  fun showLockIcon(context: Context): Boolean =
    prefs(context).getBoolean(KEY_SHOW_LOCK_ICON, false)

  fun setShowLockIcon(context: Context, show: Boolean) {
    prefs(context).edit().putBoolean(KEY_SHOW_LOCK_ICON, show).apply()
  }

  fun pausedIconPosition(context: Context): Pair<Int, Int>? {
    val preferences = prefs(context)
    if (!preferences.contains(KEY_PAUSED_ICON_X) || !preferences.contains(KEY_PAUSED_ICON_Y)) return null
    return Pair(
      preferences.getInt(KEY_PAUSED_ICON_X, 0),
      preferences.getInt(KEY_PAUSED_ICON_Y, 0),
    )
  }

  fun savePausedIconPosition(context: Context, x: Int, y: Int) {
    prefs(context).edit()
      .putInt(KEY_PAUSED_ICON_X, x)
      .putInt(KEY_PAUSED_ICON_Y, y)
      .apply()
  }

  fun geometry(
    context: Context,
    orientation: Int = context.resources.configuration.orientation,
  ): Geometry {
    val density = context.resources.displayMetrics.density
    val defaultWidth = (180 * density).toInt()
    val defaultHeight = (120 * density).toInt()
    val p = prefs(context)
    val suffix = orientationSuffix(orientation)
    val xKey = "${KEY_X}_$suffix"
    val yKey = "${KEY_Y}_$suffix"
    val widthKey = "${KEY_WIDTH}_$suffix"
    val heightKey = "${KEY_HEIGHT}_$suffix"
    val hasSavedOrientation = p.contains(xKey) && p.contains(yKey) &&
      p.contains(widthKey) && p.contains(heightKey)
    return Geometry(
      x = p.getInt(if (hasSavedOrientation) xKey else KEY_X, (24 * density).toInt()),
      y = p.getInt(if (hasSavedOrientation) yKey else KEY_Y, (110 * density).toInt()),
      width = p.getInt(if (hasSavedOrientation) widthKey else KEY_WIDTH, defaultWidth),
      height = p.getInt(if (hasSavedOrientation) heightKey else KEY_HEIGHT, defaultHeight),
    )
  }

  fun saveGeometry(
    context: Context,
    geometry: Geometry,
    orientation: Int = context.resources.configuration.orientation,
  ) {
    val suffix = orientationSuffix(orientation)
    prefs(context).edit()
      .putInt("${KEY_X}_$suffix", geometry.x)
      .putInt("${KEY_Y}_$suffix", geometry.y)
      .putInt("${KEY_WIDTH}_$suffix", geometry.width)
      .putInt("${KEY_HEIGHT}_$suffix", geometry.height)
      .apply()
  }

  fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

  fun setLastError(context: Context, message: String) {
    prefs(context).edit().putString(KEY_LAST_ERROR, message).commit()
  }

  fun clearLastError(context: Context) {
    prefs(context).edit().remove(KEY_LAST_ERROR).commit()
  }

  fun markCleanupBlocked(context: Context) {
    prefs(context).edit()
      .putInt(KEY_CLEANUP_BLOCKED_BOOT_COUNT, currentBootCount(context))
      .commit()
  }

  fun isCleanupBlocked(context: Context): Boolean {
    val preferences = prefs(context)
    if (!preferences.contains(KEY_CLEANUP_BLOCKED_BOOT_COUNT)) return false
    val blockedBootCount = preferences.getInt(KEY_CLEANUP_BLOCKED_BOOT_COUNT, Int.MIN_VALUE)
    val bootCount = currentBootCount(context)
    if (bootCount >= 0 && blockedBootCount >= 0 && bootCount != blockedBootCount) {
      preferences.edit().remove(KEY_CLEANUP_BLOCKED_BOOT_COUNT).commit()
      return false
    }
    return true
  }

  fun reset(context: Context) {
    prefs(context).edit()
      .remove(KEY_X)
      .remove(KEY_Y)
      .remove(KEY_WIDTH)
      .remove(KEY_HEIGHT)
      .remove("${KEY_X}_portrait")
      .remove("${KEY_Y}_portrait")
      .remove("${KEY_WIDTH}_portrait")
      .remove("${KEY_HEIGHT}_portrait")
      .remove("${KEY_X}_landscape")
      .remove("${KEY_Y}_landscape")
      .remove("${KEY_WIDTH}_landscape")
      .remove("${KEY_HEIGHT}_landscape")
      .remove(KEY_LOCKED)
      .remove(KEY_PAUSED_ICON_X)
      .remove(KEY_PAUSED_ICON_Y)
      .apply()
  }

  private fun currentBootCount(context: Context) = Settings.Global.getInt(
    context.contentResolver,
    Settings.Global.BOOT_COUNT,
    -1,
  )

  private fun orientationSuffix(orientation: Int) =
    if (orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
