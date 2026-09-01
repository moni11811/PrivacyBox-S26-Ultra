package com.moni11811.privacybox

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowPolicyTest {
  @Test
  fun lockedSurfaceIsNonTouchableAndWithinObscuringLimit() {
    val state = OverlayWindowPolicy.surface(touchable = false, maximumObscuringOpacity = 0.8f)

    assertTrue(state.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
    assertTrue(state.alpha <= 0.8f)
  }

  @Test
  fun unlockedSurfaceRestoresTouchAndFullAlpha() {
    val state = OverlayWindowPolicy.surface(touchable = true, maximumObscuringOpacity = 0.8f)

    assertFalse(state.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
    assertEquals(1f, state.alpha)
  }
}
