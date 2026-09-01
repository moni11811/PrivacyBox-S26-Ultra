package com.moni11811.privacybox

import android.view.WindowManager

internal data class OverlayWindowState(
  val flags: Int,
  val alpha: Float,
)

internal object OverlayWindowPolicy {
  fun surface(touchable: Boolean, maximumObscuringOpacity: Float): OverlayWindowState {
    val flags = BASE_FLAGS or if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    return OverlayWindowState(
      flags = flags,
      alpha = if (touchable) 1f else maximumObscuringOpacity.coerceIn(0f, 1f),
    )
  }

  private const val BASE_FLAGS =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
}
