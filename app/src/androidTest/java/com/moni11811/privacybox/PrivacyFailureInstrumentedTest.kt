package com.moni11811.privacybox

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyFailureInstrumentedTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @After
  fun tearDown() {
    PrivacyOverlayService.stop(context)
    PrivacyOverlayService.resetStateAfterInstrumentedTest(context)
  }

  @Test
  fun missingSamsungCapabilityFailsClosedOnDevice() {
    assumeTrue("Display-over-other-apps permission is required for this device test", Settings.canDrawOverlays(context))
    PrivacyOverlayService.stop(context)
    SystemClock.sleep(200)
    OverlayState.clearLastError(context)
    PrivacyOverlayService.privacyApiFactory = {
      Result.failure(NoSuchMethodException("simulated missing Samsung privacy method"))
    }

    PrivacyOverlayService.start(context)
    waitForState(PrivacyActivationState.ERROR)

    assertEquals(PrivacyActivationState.ERROR, PrivacyOverlayService.activationState)
    assertFalse(PrivacyOverlayService.isRunning)
    assertTrue(OverlayState.lastError(context)?.contains("unavailable") == true)
  }

  private fun waitForState(expected: PrivacyActivationState) {
    val deadline = SystemClock.elapsedRealtime() + 5_000L
    while (PrivacyOverlayService.activationState != expected && SystemClock.elapsedRealtime() < deadline) {
      SystemClock.sleep(50)
    }
  }
}
