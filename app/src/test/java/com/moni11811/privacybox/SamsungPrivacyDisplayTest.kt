package com.moni11811.privacybox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungPrivacyDisplayTest {
  @Test
  fun resolveRequiresEverySamsungMethod() {
    assertTrue(ReflectiveSamsungPrivacyDisplayApi.resolve(MissingActivate::class.java).isFailure)
    assertTrue(ReflectiveSamsungPrivacyDisplayApi.resolve(MissingPosition::class.java).isFailure)
    assertTrue(ReflectiveSamsungPrivacyDisplayApi.resolve(MissingDisable::class.java).isFailure)
  }

  @Test
  fun activeStateRequiresSuccessfulActivateAndPosition() {
    val target = WorkingSamsungTarget()
    val api = ReflectiveSamsungPrivacyDisplayApi.resolve(WorkingSamsungTarget::class.java).getOrThrow()
    val session = PrivacyDisplaySession(api, target)

    assertTrue(session.activate(REGION).isSuccess)
    assertEquals(PrivacyDisplaySessionState.ACTIVE, session.state)
    assertEquals(listOf("activate", "position"), target.calls)
  }

  @Test
  fun positionFailureNeverReportsActive() {
    val target = ThrowingPositionTarget()
    val api = ReflectiveSamsungPrivacyDisplayApi.resolve(ThrowingPositionTarget::class.java).getOrThrow()
    val session = PrivacyDisplaySession(api, target)

    assertTrue(session.activate(REGION).isFailure)
    assertEquals(PrivacyDisplaySessionState.FAILED, session.state)
  }

  @Test
  fun disableFailureIsTerminal() {
    val target = ThrowingDisableTarget()
    val api = ReflectiveSamsungPrivacyDisplayApi.resolve(ThrowingDisableTarget::class.java).getOrThrow()
    val session = PrivacyDisplaySession(api, target)
    assertTrue(session.activate(REGION).isSuccess)

    assertTrue(session.suspendForMotion().isFailure)
    assertEquals(PrivacyDisplaySessionState.FAILED, session.state)
  }

  @Test
  fun disposeDisablesOnceAndIsIdempotent() {
    val api = FakePrivacyDisplayApi()
    val session = PrivacyDisplaySession(api, Any())
    assertTrue(session.activate(REGION).isSuccess)

    assertTrue(session.dispose().isSuccess)
    assertTrue(session.dispose().isSuccess)
    assertEquals(1, api.disableCalls)
    assertEquals(PrivacyDisplaySessionState.DISPOSED, session.state)
  }

  @Test
  fun failedPartialActivationStillAttemptsDisableDuringDispose() {
    val api = FakePrivacyDisplayApi(activationSucceeds = false)
    val session = PrivacyDisplaySession(api, Any())
    assertTrue(session.activate(REGION).isFailure)

    assertTrue(session.dispose().isSuccess)
    assertEquals(1, api.disableCalls)
    assertEquals(PrivacyDisplaySessionState.DISPOSED, session.state)
  }

  @Test
  fun suspendedSessionDoesNotDisableTwiceDuringDispose() {
    val api = FakePrivacyDisplayApi()
    val session = PrivacyDisplaySession(api, Any())
    assertTrue(session.activate(REGION).isSuccess)
    assertTrue(session.suspendForMotion().isSuccess)

    assertTrue(session.dispose().isSuccess)
    assertEquals(1, api.disableCalls)
  }

  @Test
  fun pausedSessionCanReactivateBeforeFinalDispose() {
    val api = FakePrivacyDisplayApi()
    val session = PrivacyDisplaySession(api, Any())
    assertTrue(session.activate(REGION).isSuccess)
    assertTrue(session.suspendForMotion().isSuccess)

    assertTrue(session.activate(REGION).isSuccess)
    assertEquals(PrivacyDisplaySessionState.ACTIVE, session.state)
  }

  companion object {
    private val REGION = PrivacyDisplayRegion(12f, 2, 2, 100, 80)
  }
}

private class WorkingSamsungTarget {
  val calls = mutableListOf<String>()
  fun semSetPrivacyDisplayView(radius: Float) { calls += "activate" }
  fun semSetPrivacyDisplayViewPosition(left: Int, top: Int, right: Int, bottom: Int) { calls += "position" }
  fun semDisablePrivacyDisplayView() { calls += "disable" }
}

private class ThrowingPositionTarget {
  fun semSetPrivacyDisplayView(radius: Float) = Unit
  fun semSetPrivacyDisplayViewPosition(left: Int, top: Int, right: Int, bottom: Int) {
    error("position failed")
  }
  fun semDisablePrivacyDisplayView() = Unit
}

private class ThrowingDisableTarget {
  fun semSetPrivacyDisplayView(radius: Float) = Unit
  fun semSetPrivacyDisplayViewPosition(left: Int, top: Int, right: Int, bottom: Int) = Unit
  fun semDisablePrivacyDisplayView() { error("disable failed") }
}

private class MissingActivate {
  fun semSetPrivacyDisplayViewPosition(left: Int, top: Int, right: Int, bottom: Int) = Unit
  fun semDisablePrivacyDisplayView() = Unit
}

private class MissingPosition {
  fun semSetPrivacyDisplayView(radius: Float) = Unit
  fun semDisablePrivacyDisplayView() = Unit
}

private class MissingDisable {
  fun semSetPrivacyDisplayView(radius: Float) = Unit
  fun semSetPrivacyDisplayViewPosition(left: Int, top: Int, right: Int, bottom: Int) = Unit
}

private class FakePrivacyDisplayApi(
  private val activationSucceeds: Boolean = true,
) : PrivacyDisplayApi {
  var disableCalls = 0
  override fun activate(target: Any, region: PrivacyDisplayRegion) = if (activationSucceeds) {
    Result.success(Unit)
  } else {
    Result.failure(IllegalStateException("activation failed"))
  }
  override fun disable(target: Any): Result<Unit> {
    disableCalls += 1
    return Result.success(Unit)
  }
}
