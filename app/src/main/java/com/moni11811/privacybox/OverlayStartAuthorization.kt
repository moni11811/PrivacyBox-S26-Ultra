package com.moni11811.privacybox

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

internal enum class OverlayPermissionAction {
  NONE,
  OPEN_SETTINGS,
  START_OVERLAY,
}

internal enum class OverlayPermissionSource {
  APP,
  TILE,
}

internal enum class OverlayPermissionPhase {
  OPEN_SETTINGS,
  AWAITING_RESULT,
}

internal data class PendingOverlayPermission(
  val source: OverlayPermissionSource,
  val phase: OverlayPermissionPhase,
  val bootCount: Int,
  val issuedAt: Long,
  val expiresAt: Long,
)

internal interface OverlayPermissionStateStore {
  fun read(): PendingOverlayPermission?
  fun write(value: PendingOverlayPermission): Boolean
  fun clear(): Boolean
}

internal class OverlayPermissionFlow(
  private val store: OverlayPermissionStateStore,
  private val elapsedRealtime: () -> Long,
  private val bootCount: () -> Int,
  private val ttlMillis: Long = AUTHORIZATION_TTL_MS,
) {
  fun requestFromApp(): Boolean = request(
    source = OverlayPermissionSource.APP,
    phase = OverlayPermissionPhase.AWAITING_RESULT,
  )

  fun requestFromTile(): Boolean = request(
    source = OverlayPermissionSource.TILE,
    phase = OverlayPermissionPhase.OPEN_SETTINGS,
  )

  fun onResume(hasOverlayPermission: Boolean): OverlayPermissionAction {
    val pending = store.read() ?: return OverlayPermissionAction.NONE
    val now = elapsedRealtime()
    if (bootCount() != pending.bootCount || now < pending.issuedAt || now > pending.expiresAt) {
      store.clear()
      return OverlayPermissionAction.NONE
    }

    if (hasOverlayPermission) {
      return if (store.clear()) OverlayPermissionAction.START_OVERLAY else OverlayPermissionAction.NONE
    }

    return when (pending.phase) {
      OverlayPermissionPhase.OPEN_SETTINGS -> {
        if (store.write(pending.copy(phase = OverlayPermissionPhase.AWAITING_RESULT))) {
          OverlayPermissionAction.OPEN_SETTINGS
        } else {
          store.clear()
          OverlayPermissionAction.NONE
        }
      }
      OverlayPermissionPhase.AWAITING_RESULT -> {
        store.clear()
        OverlayPermissionAction.NONE
      }
    }
  }

  private fun request(source: OverlayPermissionSource, phase: OverlayPermissionPhase): Boolean {
    val issuedAt = elapsedRealtime()
    val currentBootCount = bootCount()
    if (currentBootCount < 0) return false
    return store.write(
      PendingOverlayPermission(
        source = source,
        phase = phase,
        bootCount = currentBootCount,
        issuedAt = issuedAt,
        expiresAt = issuedAt + ttlMillis,
      ),
    )
  }

  companion object {
    internal const val AUTHORIZATION_TTL_MS = 120_000L
  }
}

internal object OverlayStartAuthorization {
  fun requestFromApp(context: Context): Boolean = flow(context).requestFromApp()

  fun requestFromTile(context: Context): Boolean = flow(context).requestFromTile()

  fun onResume(context: Context, hasOverlayPermission: Boolean): OverlayPermissionAction =
    flow(context).onResume(hasOverlayPermission)

  private fun flow(context: Context) = OverlayPermissionFlow(
    store = SharedPreferencesOverlayPermissionStore(
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    ),
    elapsedRealtime = SystemClock::elapsedRealtime,
    bootCount = {
      Settings.Global.getInt(
        context.contentResolver,
        Settings.Global.BOOT_COUNT,
        -1,
      )
    },
  )

  private const val PREFS_NAME = "overlay_start_authorization"
}

private class SharedPreferencesOverlayPermissionStore(
  private val preferences: SharedPreferences,
) : OverlayPermissionStateStore {
  override fun read(): PendingOverlayPermission? {
    val source = preferences.getString(KEY_SOURCE, null)
      ?.let { runCatching { OverlayPermissionSource.valueOf(it) }.getOrNull() }
      ?: return null
    val phase = preferences.getString(KEY_PHASE, null)
      ?.let { runCatching { OverlayPermissionPhase.valueOf(it) }.getOrNull() }
      ?: return null
    val bootCount = preferences.getInt(KEY_BOOT_COUNT, -1)
    val issuedAt = preferences.getLong(KEY_ISSUED_AT, -1L)
    val expiresAt = preferences.getLong(KEY_EXPIRES_AT, -1L)
    if (bootCount < 0 || issuedAt < 0L || expiresAt < issuedAt) return null
    return PendingOverlayPermission(source, phase, bootCount, issuedAt, expiresAt)
  }

  override fun write(value: PendingOverlayPermission): Boolean = preferences.edit()
    .putString(KEY_SOURCE, value.source.name)
    .putString(KEY_PHASE, value.phase.name)
    .putInt(KEY_BOOT_COUNT, value.bootCount)
    .putLong(KEY_ISSUED_AT, value.issuedAt)
    .putLong(KEY_EXPIRES_AT, value.expiresAt)
    .commit()

  override fun clear(): Boolean = preferences.edit().clear().commit()

  companion object {
    private const val KEY_SOURCE = "source"
    private const val KEY_PHASE = "phase"
    private const val KEY_BOOT_COUNT = "boot_count"
    private const val KEY_ISSUED_AT = "issued_at"
    private const val KEY_EXPIRES_AT = "expires_at"
  }
}
