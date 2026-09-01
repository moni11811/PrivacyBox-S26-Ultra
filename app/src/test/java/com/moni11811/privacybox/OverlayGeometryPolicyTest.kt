package com.moni11811.privacybox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryPolicyTest {
  @Test
  fun bottomControlsUseDisplayedSurfaceTopAfterStatusBarClamping() {
    val controlY = OverlayGeometryPolicy.bottomAnchoredY(
      surfaceY = 8,
      surfaceHeight = 2_435,
      controlHeight = 135,
      statusBarInsetTop = 140,
    )

    assertEquals(2_440, controlY)
    assertEquals(2_575, controlY + 135)
  }

  @Test
  fun compactPauseKeepsItsNormalVerticalSlot() {
    assertEquals(
      155,
      OverlayGeometryPolicy.compactTopAnchoredY(
        surfaceY = 8,
        edgeInset = 8,
        controlPadding = 15,
        statusBarInsetTop = 140,
      ),
    )
  }

  @Test
  fun navigationBarAndPrivacyGuardAreRemovedFromAvailableBottom() {
    assertEquals(
      Pair(1_440, 3_032),
      OverlayGeometryPolicy.availableSize(
        boundsWidth = 1_440,
        boundsHeight = 3_120,
        systemInsetRight = 0,
        navigationInsetBottom = 56,
        bottomGuard = 32,
      ),
    )
  }

  @Test
  fun landscapeRightNavigationInsetIsRemovedFromAvailableWidth() {
    assertEquals(
      Pair(3_064, 1_408),
      OverlayGeometryPolicy.availableSize(
        boundsWidth = 3_120,
        boundsHeight = 1_440,
        systemInsetRight = 56,
        navigationInsetBottom = 0,
        bottomGuard = 32,
      ),
    )
  }

  @Test
  fun eachCornerSelectsItsResizeHandle() {
    assertEquals(ResizeCorner.TOP_LEFT, OverlayGeometryPolicy.resizeCornerAt(20f, 20f, 1_000, 800, 100f))
    assertEquals(ResizeCorner.TOP_RIGHT, OverlayGeometryPolicy.resizeCornerAt(980f, 20f, 1_000, 800, 100f))
    assertEquals(ResizeCorner.BOTTOM_LEFT, OverlayGeometryPolicy.resizeCornerAt(20f, 780f, 1_000, 800, 100f))
    assertEquals(ResizeCorner.BOTTOM_RIGHT, OverlayGeometryPolicy.resizeCornerAt(980f, 780f, 1_000, 800, 100f))
    assertNull(OverlayGeometryPolicy.resizeCornerAt(80f, 80f, 1_000, 800, 100f))
    assertNull(OverlayGeometryPolicy.resizeCornerAt(500f, 400f, 1_000, 800, 100f))
  }

  @Test
  fun eachCornerResizesWhileKeepingItsOppositeCornerFixed() {
    val start = OverlayState.Geometry(x = 100, y = 200, width = 500, height = 400)
    fun resize(corner: ResizeCorner) = OverlayGeometryPolicy.resize(
      start = start,
      corner = corner,
      dx = 50,
      dy = 30,
      availableWidth = 2_000,
      availableHeight = 2_000,
      edgeInset = 8,
      minimumX = 8,
      minimumY = 140,
      minimumWidth = 100,
      minimumHeight = 100,
    )

    assertEquals(OverlayState.Geometry(150, 230, 450, 370), resize(ResizeCorner.TOP_LEFT))
    assertEquals(OverlayState.Geometry(100, 230, 550, 370), resize(ResizeCorner.TOP_RIGHT))
    assertEquals(OverlayState.Geometry(150, 200, 450, 430), resize(ResizeCorner.BOTTOM_LEFT))
    assertEquals(OverlayState.Geometry(100, 200, 550, 430), resize(ResizeCorner.BOTTOM_RIGHT))
  }

  @Test
  fun oversizedBottomOverlappingGeometryIsClampedInsideUsableDisplay() {
    val result = OverlayGeometryPolicy.sanitize(
      input = OverlayState.Geometry(x = 8, y = 8, width = 1_424, height = 2_931),
      availableWidth = 1_440,
      availableHeight = 3_032,
      edgeInset = 8,
      minimumX = 8,
      minimumY = 140,
      minimumWidth = 495,
      minimumHeight = 270,
    )

    assertEquals(1_424, result.width)
    assertEquals(2_884, result.height)
    assertEquals(140, result.y)
    assertTrue(result.x + result.width + 8 <= 1_440)
    assertTrue(result.y + result.height + 8 <= 3_032)
  }

  @Test
  fun resizeStopsAboveNavigationBarBoundary() {
    val result = OverlayGeometryPolicy.resize(
      start = OverlayState.Geometry(x = 8, y = 140, width = 1_424, height = 2_884),
      corner = ResizeCorner.BOTTOM_RIGHT,
      dx = 500,
      dy = 5_000,
      availableWidth = 1_440,
      availableHeight = 3_032,
      edgeInset = 8,
      minimumX = 8,
      minimumY = 140,
      minimumWidth = 495,
      minimumHeight = 270,
    )

    assertEquals(1_424, result.width)
    assertEquals(2_884, result.height)
    assertEquals(3_032, result.y + result.height + 8)
  }
}
