package com.lperezmo.voicerelay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that hides and shows every floating button (the dictation
 * bubble and its pills), for a screen recording or a clean screenshot. On means
 * the buttons are showing. When the accessibility service is off (after a force
 * stop, for one) the tile says so, and a tap opens Accessibility settings to
 * turn it back on.
 */
class ButtonsTileService : TileService() {

    companion object {
        /** Asks Android to redraw the tile; it calls [onStartListening] when it can. */
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, ButtonsTileService::class.java))
            } catch (_: Exception) {
                // No tile added, or the system is not ready: nothing to redraw.
            }
        }
    }

    override fun onStartListening() = render()

    override fun onClick() {
        val service = WhisperAccessibilityService.instance
        if (service == null) {
            openAccessibilitySettings()
            return
        }
        service.setButtonsHidden(!service.buttonsHidden)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val service = WhisperAccessibilityService.instance
        tile.label = getString(R.string.buttons_tile_label)
        tile.state = when {
            service == null -> Tile.STATE_INACTIVE
            service.buttonsHidden -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.subtitle = getString(
            when {
                service == null -> R.string.buttons_tile_service_off
                service.buttonsHidden -> R.string.buttons_tile_hidden
                else -> R.string.buttons_tile_shown
            }
        )
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
