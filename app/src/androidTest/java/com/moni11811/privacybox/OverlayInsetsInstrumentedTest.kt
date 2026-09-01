package com.moni11811.privacybox

import android.content.Context
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayInsetsInstrumentedTest {
  @Test
  fun availableAreaExcludesStableNavigationBarAndBottomGuard() {
    val context: Context = ApplicationProvider.getApplicationContext()
    val metrics = context.getSystemService(WindowManager::class.java).maximumWindowMetrics
    val systemBars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
    val navigationBars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
    val displayCutout = metrics.windowInsets.displayCutout
    val edgeInset = (2 * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(2)
    val bottomGuard = edgeInset * 4
    val available = OverlayGeometryPolicy.availableSize(
      boundsWidth = metrics.bounds.width(),
      boundsHeight = metrics.bounds.height(),
      systemInsetRight = maxOf(systemBars.right, displayCutout?.safeInsetRight ?: 0),
      navigationInsetBottom = navigationBars.bottom,
      bottomGuard = bottomGuard,
    )

    assertEquals(
      metrics.bounds.width() - maxOf(systemBars.right, displayCutout?.safeInsetRight ?: 0),
      available.first,
    )
    assertTrue(available.second < metrics.bounds.height())
    Log.i(
      "PrivacyBoxInsetsTest",
      "bounds=${metrics.bounds.width()}x${metrics.bounds.height()} systemBars=$systemBars " +
        "navigationBars=$navigationBars bottomGuard=$bottomGuard available=$available",
    )
  }
}
