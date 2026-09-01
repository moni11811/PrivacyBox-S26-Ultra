package com.moni11811.privacybox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPermissionFlowTest {
  private var now = 1_000L
  private var bootCount = 7
  private val store = InMemoryOverlayPermissionStore()

  @Test
  fun coldExternalResumeWithPermissionCannotStartOverlay() {
    val flow = flow()

    assertEquals(OverlayPermissionAction.NONE, flow.onResume(hasOverlayPermission = true))
    assertNull(store.value)
  }

  @Test
  fun tileFlowSurvivesRecreationAndStartsOnlyOnceAfterGrant() {
    assertTrue(flow().requestFromTile())
    assertEquals(OverlayPermissionAction.OPEN_SETTINGS, flow().onResume(hasOverlayPermission = false))
    assertEquals(OverlayPermissionPhase.AWAITING_RESULT, store.value?.phase)

    val recreatedFlow = flow()
    assertEquals(OverlayPermissionAction.START_OVERLAY, recreatedFlow.onResume(hasOverlayPermission = true))
    assertNull(store.value)
    assertEquals(OverlayPermissionAction.NONE, recreatedFlow.onResume(hasOverlayPermission = true))
  }

  @Test
  fun deniedAppFlowClearsAuthorization() {
    assertTrue(flow().requestFromApp())

    assertEquals(OverlayPermissionAction.NONE, flow().onResume(hasOverlayPermission = false))
    assertNull(store.value)
  }

  @Test
  fun expiredOrRebootedAuthorizationFailsClosed() {
    assertTrue(flow().requestFromTile())
    now += OverlayPermissionFlow.AUTHORIZATION_TTL_MS + 1
    assertEquals(OverlayPermissionAction.NONE, flow().onResume(hasOverlayPermission = true))
    assertNull(store.value)

    now = 1_000L
    assertTrue(flow().requestFromApp())
    bootCount += 1
    assertEquals(OverlayPermissionAction.NONE, flow().onResume(hasOverlayPermission = true))
    assertNull(store.value)
  }

  @Test
  fun storeWriteFailureNeverCreatesAuthority() {
    store.allowWrites = false

    assertFalse(flow().requestFromApp())
    assertNull(store.value)
  }

  @Test
  fun clearFailureNeverGrantsOrRepeatsAuthority() {
    assertTrue(flow().requestFromApp())
    store.allowClear = false

    assertEquals(OverlayPermissionAction.NONE, flow().onResume(hasOverlayPermission = true))
    assertEquals(OverlayPermissionAction.NONE, flow().onResume(hasOverlayPermission = true))
  }

  private fun flow() = OverlayPermissionFlow(
    store = store,
    elapsedRealtime = { now },
    bootCount = { bootCount },
  )
}

private class InMemoryOverlayPermissionStore : OverlayPermissionStateStore {
  var value: PendingOverlayPermission? = null
  var allowWrites = true
  var allowClear = true

  override fun read(): PendingOverlayPermission? = value

  override fun write(value: PendingOverlayPermission): Boolean {
    if (!allowWrites) return false
    this.value = value
    return true
  }

  override fun clear(): Boolean {
    if (!allowClear) return false
    value = null
    return true
  }
}
