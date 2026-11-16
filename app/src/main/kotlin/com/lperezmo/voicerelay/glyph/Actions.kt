package com.lperezmo.voicerelay.glyph

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.lperezmo.voicerelay.WakeControlActivity

/** Runs an [Action]. Called from the canvas while it is in front, so it may start activities. */
object Actions {
    const val TAG = "Glyph"

    // Phone Relay's exported share target ("Send to Claude"), which queues text in its inbox.
    const val RELAY_PKG = "com.lperezmo.phonerelay"
    const val RELAY_SHARE = "com.lperezmo.phonerelay.ShareActivity"

    // Fridge Labels' exported print receiver (no permission); with no text it prints the date.
    const val LABELS_PKG = "com.lperezmo.fridgelabels"
    const val LABELS_RECEIVER = "com.lperezmo.fridgelabels.PrintReceiver"
    const val LABELS_PRINT = "com.lperezmo.fridgelabels.PRINT"

    /** Returns false (after saying why) when the action could not start. */
    fun run(activity: Activity, action: Action): Boolean = try {
        when (action) {
            Action.Nothing -> {
                toast(activity, "This shape has no action yet. Set one in Glyph settings.")
                false
            }
            is Action.OpenApp -> {
                val intent = activity.packageManager.getLaunchIntentForPackage(action.pkg)
                if (intent == null) {
                    toast(activity, "${action.name} is not installed.")
                    false
                } else {
                    activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }
            }
            is Action.Url -> {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }
            Action.RelayClipboard -> sendClipboard(activity)
            Action.WakeToggle -> toggleWakeWord(activity)
            Action.PrintDateLabel -> printDateLabel(activity)
            // the canvas turns itself into a page for this one when the feature is on (see PageController)
            Action.Ask -> {
                toast(activity, "Ask the Page is off. Turn it on in Voice Relay's settings.")
                false
            }
        }
    } catch (e: ActivityNotFoundException) {
        toast(activity, "Nothing on this phone opens that.")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "action refused: ${e.message}")
        toast(activity, "Android refused: ${e.message}")
        false
    }

    fun relayInstalled(context: Context) = installed(context, RELAY_PKG)

    /** Only works while our window has focus: Android lets nothing else read the clipboard. */
    private fun sendClipboard(activity: Activity): Boolean {
        if (!relayInstalled(activity)) return false.also { toast(activity, "Phone Relay is not installed.") }
        val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()
        if (text.isNullOrBlank()) return false.also { toast(activity, "The clipboard is empty.") }
        activity.startActivity(
            Intent(Intent.ACTION_SEND).apply {
                setClassName(RELAY_PKG, RELAY_SHARE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        return true
    }

    /**
     * Voice Relay's own wake word, through the non-exported alias its notifications use
     * ([WakeControlActivity.INTERNAL_ALIAS]), so it works even when other apps may not
     * control Voice Relay. WakeControlActivity does the toggle and its own checks.
     */
    private fun toggleWakeWord(activity: Activity): Boolean {
        activity.startActivity(
            Intent().setClassName(activity, WakeControlActivity.INTERNAL_ALIAS)
                .setAction(WakeControlActivity.ACTION_WAKE_TOGGLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    fun fridgeLabelsInstalled(context: Context) = installed(context, LABELS_PKG)

    /** Copies goes as a string, the way Tasker sent it; Fridge Labels takes a string or a number. */
    private fun printDateLabel(context: Context): Boolean {
        if (!fridgeLabelsInstalled(context)) return false.also { toast(context, "Fridge Labels is not installed.") }
        context.sendBroadcast(
            Intent(LABELS_PRINT).apply {
                setClassName(LABELS_PKG, LABELS_RECEIVER)
                putExtra("copies", "1")
            },
        )
        return true
    }

    private fun installed(context: Context, pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun toast(context: Context, text: String) =
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
}
