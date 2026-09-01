package com.moni11811.privacybox

internal enum class ResizeCorner {
  TOP_LEFT,
  TOP_RIGHT,
  BOTTOM_LEFT,
  BOTTOM_RIGHT,
}

internal object OverlayGeometryPolicy {
  fun bottomAnchoredY(
    surfaceY: Int,
    surfaceHeight: Int,
    controlHeight: Int,
    statusBarInsetTop: Int,
  ): Int = maxOf(surfaceY, statusBarInsetTop) + (surfaceHeight - controlHeight).coerceAtLeast(0)

  fun compactTopAnchoredY(
    surfaceY: Int,
    edgeInset: Int,
    controlPadding: Int,
    statusBarInsetTop: Int,
  ): Int = maxOf(surfaceY + edgeInset, statusBarInsetTop) + controlPadding

  fun availableSize(
    boundsWidth: Int,
    boundsHeight: Int,
    systemInsetRight: Int,
    navigationInsetBottom: Int,
    bottomGuard: Int,
  ): Pair<Int, Int> = Pair(
    (boundsWidth - systemInsetRight).coerceAtLeast(1),
    (boundsHeight - navigationInsetBottom - bottomGuard).coerceAtLeast(1),
  )

  fun resizeCornerAt(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
    handleSize: Float,
  ): ResizeCorner? {
    val rightDistance = width - x
    val bottomDistance = height - y
    val radiusSquared = handleSize * handleSize
    fun insideCircle(horizontal: Float, vertical: Float) =
      horizontal * horizontal + vertical * vertical <= radiusSquared
    return when {
      insideCircle(x, y) -> ResizeCorner.TOP_LEFT
      insideCircle(rightDistance, y) -> ResizeCorner.TOP_RIGHT
      insideCircle(x, bottomDistance) -> ResizeCorner.BOTTOM_LEFT
      insideCircle(rightDistance, bottomDistance) -> ResizeCorner.BOTTOM_RIGHT
      else -> null
    }
  }

  fun sanitize(
    input: OverlayState.Geometry,
    availableWidth: Int,
    availableHeight: Int,
    edgeInset: Int,
    minimumX: Int,
    minimumY: Int,
    minimumWidth: Int,
    minimumHeight: Int,
  ): OverlayState.Geometry {
    val maximumWidth = (availableWidth - minimumX - edgeInset).coerceAtLeast(minimumWidth)
    val maximumHeight = (availableHeight - minimumY - edgeInset).coerceAtLeast(minimumHeight)
    val width = input.width.coerceIn(minimumWidth, maximumWidth)
    val height = input.height.coerceIn(minimumHeight, maximumHeight)
    return OverlayState.Geometry(
      x = input.x.coerceIn(minimumX, (availableWidth - width - edgeInset).coerceAtLeast(minimumX)),
      y = input.y.coerceIn(minimumY, (availableHeight - height - edgeInset).coerceAtLeast(minimumY)),
      width = width,
      height = height,
    )
  }

  fun resize(
    start: OverlayState.Geometry,
    corner: ResizeCorner,
    dx: Int,
    dy: Int,
    availableWidth: Int,
    availableHeight: Int,
    edgeInset: Int,
    minimumX: Int,
    minimumY: Int,
    minimumWidth: Int,
    minimumHeight: Int,
  ): OverlayState.Geometry {
    val startRight = start.x + start.width
    val startBottom = start.y + start.height
    val left = if (corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.BOTTOM_LEFT) {
      (start.x + dx).coerceIn(minimumX, startRight - minimumWidth)
    } else {
      start.x
    }
    val top = if (corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.TOP_RIGHT) {
      (start.y + dy).coerceIn(minimumY, startBottom - minimumHeight)
    } else {
      start.y
    }
    val right = if (corner == ResizeCorner.TOP_RIGHT || corner == ResizeCorner.BOTTOM_RIGHT) {
      (startRight + dx).coerceIn(start.x + minimumWidth, availableWidth - edgeInset)
    } else {
      startRight
    }
    val bottom = if (corner == ResizeCorner.BOTTOM_LEFT || corner == ResizeCorner.BOTTOM_RIGHT) {
      (startBottom + dy).coerceIn(start.y + minimumHeight, availableHeight - edgeInset)
    } else {
      startBottom
    }
    return OverlayState.Geometry(
      x = left,
      y = top,
      width = right - left,
      height = bottom - top,
    )
  }
}
