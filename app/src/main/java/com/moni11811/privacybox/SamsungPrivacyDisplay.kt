package com.moni11811.privacybox

import android.view.View
import java.lang.reflect.Method

internal data class PrivacyDisplayRegion(
  val cornerRadius: Float,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
)

internal interface PrivacyDisplayApi {
  fun activate(target: Any, region: PrivacyDisplayRegion): Result<Unit>
  fun disable(target: Any): Result<Unit>
}

internal class ReflectiveSamsungPrivacyDisplayApi private constructor(
  private val activateMethod: Method,
  private val positionMethod: Method,
  private val disableMethod: Method,
) : PrivacyDisplayApi {
  override fun activate(target: Any, region: PrivacyDisplayRegion): Result<Unit> = runCatching {
    activateMethod.invoke(target, region.cornerRadius)
    positionMethod.invoke(target, region.left, region.top, region.right, region.bottom)
    Unit
  }

  override fun disable(target: Any): Result<Unit> = runCatching {
    disableMethod.invoke(target)
    Unit
  }

  companion object {
    fun resolve(methodOwner: Class<*> = View::class.java): Result<ReflectiveSamsungPrivacyDisplayApi> =
      runCatching {
        ReflectiveSamsungPrivacyDisplayApi(
          activateMethod = methodOwner.getMethod(
            "semSetPrivacyDisplayView",
            Float::class.javaPrimitiveType,
          ),
          positionMethod = methodOwner.getMethod(
            "semSetPrivacyDisplayViewPosition",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
          ),
          disableMethod = methodOwner.getMethod("semDisablePrivacyDisplayView"),
        )
      }
  }
}

internal enum class PrivacyDisplaySessionState {
  READY,
  ACTIVE,
  SUSPENDED,
  FAILED,
  DISPOSED,
}

internal class PrivacyDisplaySession(
  private val api: PrivacyDisplayApi,
  private val target: Any,
) {
  var state: PrivacyDisplaySessionState = PrivacyDisplaySessionState.READY
    private set

  fun activate(region: PrivacyDisplayRegion): Result<Unit> {
    if (state == PrivacyDisplaySessionState.FAILED || state == PrivacyDisplaySessionState.DISPOSED) {
      return Result.failure(IllegalStateException("Privacy display session is not activatable"))
    }
    return api.activate(target, region).also { result ->
      state = if (result.isSuccess) PrivacyDisplaySessionState.ACTIVE else PrivacyDisplaySessionState.FAILED
    }
  }

  fun suspendForMotion(): Result<Unit> {
    if (state != PrivacyDisplaySessionState.ACTIVE) {
      state = PrivacyDisplaySessionState.FAILED
      return Result.failure(IllegalStateException("Privacy display was not active before motion"))
    }
    return api.disable(target).also { result ->
      state = if (result.isSuccess) PrivacyDisplaySessionState.SUSPENDED else PrivacyDisplaySessionState.FAILED
    }
  }

  fun dispose(): Result<Unit> {
    if (state == PrivacyDisplaySessionState.DISPOSED) return Result.success(Unit)
    val shouldDisable = state == PrivacyDisplaySessionState.ACTIVE || state == PrivacyDisplaySessionState.FAILED
    state = PrivacyDisplaySessionState.DISPOSED
    return if (shouldDisable) api.disable(target) else Result.success(Unit)
  }
}
