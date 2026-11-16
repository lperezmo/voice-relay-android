package com.lperezmo.voicerelay.glyph

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lperezmo.voicerelay.AppSettings
import com.lperezmo.voicerelay.R

/**
 * Quick Settings tile "Glyph canvas": closes the shade and opens the built-in canvas.
 * The tile, like the "Glyph" launcher icon, exists only while "Use the built-in canvas"
 * is on; [setEnabled] switches both.
 */
class GlyphTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            label = getString(R.string.glyph_tile_label)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { open() } else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        val intent = Intent(this, CanvasActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(CanvasActivity.EXTRA_FROM_TILE, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** The "Glyph" launcher icon, an alias of [CanvasActivity] declared in the manifest. */
        const val LAUNCHER_ALIAS ="com.lperezmo.voicerelay.glyph.CanvasLauncher"

        /**
         * Adds or removes the "Glyph" launcher icon and this tile, so they exist exactly
         * while the built-in canvas is on. Only touches a component whose state differs,
         * so it is cheap to call on every settings refresh. Never throws.
         */
        /**
         * Brings the launcher icon and tile in line with [AppSettings.glyphBuiltIn] without
         * the switch being touched (the manifest ships them off, while the setting defaults
         * to on). Idempotent and cheap: [setEnabled] only writes a state that differs.
         * With [enableOnly] it only ever turns them on, for callers running inside the canvas,
         * which must not disable the alias they may have been started through.
         */
        fun sync(context: Context, enableOnly: Boolean = false) {
            val on = try {
                AppSettings(context).glyphBuiltIn
            } catch (_: Exception) {
                return
            }
            if (on || !enableOnly) setEnabled(context, on)
        }

        fun setEnabled(context: Context, on: Boolean) {
            val pm = context.packageManager
            val wanted = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val components = listOf(
                ComponentName(context.packageName, LAUNCHER_ALIAS),
                ComponentName(context, GlyphTileService::class.java),
            )
            for (component in components) {
                try {
                    if (pm.getComponentEnabledSetting(component) != wanted) {
                        pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
                    }
                } catch (_: Exception) {
                    // Unknown component or the package manager is busy: try again next refresh.
                }
            }
        }
    }
}
