package com.moni11811.privacybox

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class PrivacyTileService : TileService() {
  override fun onStartListening() {
    super.onStartListening()
    updateTile()
  }

  override fun onClick() {
    super.onClick()
    Log.i(TAG, "Tile clicked activationState=${PrivacyOverlayService.activationState}")
    if (PrivacyOverlayService.activationState == PrivacyActivationState.PAUSED) {
      PrivacyOverlayService.resume(this)
      updateTile()
      return
    }
    if (PrivacyOverlayService.isRunning) {
      PrivacyOverlayService.stop(this)
      updateTile()
      return
    }

    if (!Settings.canDrawOverlays(this)) {
      if (!OverlayStartAuthorization.requestFromTile(this)) {
        OverlayState.setLastError(this, "Unable to prepare the permission request. Open Privacy Box and try again.")
        updateTile()
        return
      }
      val intent = Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      startActivityAndCollapse(
        PendingIntent.getActivity(
          this,
          44,
          intent,
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
      )
      return
    }

    PrivacyOverlayService.start(this)
    updateTile()
  }

  private fun updateTile() {
    val activationState = PrivacyOverlayService.activationState
    val active = activationState == PrivacyActivationState.ACTIVE
    val lastError = OverlayState.lastError(this)
    val cleanupBlocked = OverlayState.isCleanupBlocked(this)
    qsTile?.apply {
      label = "Privacy box"
      subtitle = when {
        active -> "Visible"
        activationState == PrivacyActivationState.STARTING -> "Starting"
        activationState == PrivacyActivationState.SUSPENDED -> "Updating"
        activationState == PrivacyActivationState.PAUSED -> "Paused"
        cleanupBlocked || activationState == PrivacyActivationState.BLOCKED -> "Restart required"
        activationState == PrivacyActivationState.ERROR || lastError != null -> "Unavailable"
        else -> "Hidden"
      }
      icon = Icon.createWithResource(
        this@PrivacyTileService,
        if (active) R.drawable.ic_privacy_tile_on else R.drawable.ic_privacy_tile_off,
      )
      state = when {
        cleanupBlocked || activationState == PrivacyActivationState.BLOCKED -> Tile.STATE_UNAVAILABLE
        active -> Tile.STATE_ACTIVE
        else -> Tile.STATE_INACTIVE
      }
      updateTile()
    }
  }

  companion object {
    private const val TAG = "PrivacyBoxTile"
  }
}
