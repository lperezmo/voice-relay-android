package com.lperezmo.voicerelay.glyph

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.lperezmo.voicerelay.WhisperAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one screenshot Ask the Page may look at. The canvas calls [take] as it opens, before
 * it draws its dim layer; the picture then lives only in memory, is never written to disk
 * or logged, and is dropped by [clear] when the canvas closes. Only the parts inside a lit
 * lens ever leave the phone (see [PageImages]).
 *
 * Alongside it, [take] notes which app the canvas opened over ([app]: its name, package and
 * window title, as text) so the question can say where the reader is. Same lifetime.
 */
object ScreenGrab {
    @Volatile
    private var bitmap: Bitmap? = null

    /** Which app the canvas opened over (see [appUnder]); same lifetime as [bitmap]. */
    @Volatile
    private var app: PageContext? = null

    private val main = Handler(Looper.getMainLooper())

    /** Bumped by every [take] and [clear]: a screenshot that comes back for an older request is dropped. */
    private val request = AtomicInteger()

    /**
     * Ask the accessibility service for a screenshot of the default display. [done] runs once
     * on the main thread whether it worked, failed (no service, no permission, the
     * one-per-second limit) or threw; on failure [shot] stays null.
     */
    fun take(done: () -> Unit) {
        val called = AtomicBoolean(false)
        val finish: () -> Unit = {
            if (called.compareAndSet(false, true)) {
                if (Looper.myLooper() == Looper.getMainLooper()) done() else main.post { done() }
            }
        }
        val id = request.incrementAndGet()
        bitmap = null
        app = null
        val service = WhisperAccessibilityService.instance
        if (service == null) {
            main.post { finish() }
            return
        }
        // read now, before the canvas window exists, so it names the app underneath
        val found = appUnder(service)
        if (request.get() == id) app = found
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            // the canvas closed, or opened again, while this was on its way: forget it
                            if (request.get() == id) bitmap = copy else copy?.recycle()
                        } catch (_: Throwable) {
                            if (request.get() == id) bitmap = null
                        } finally {
                            try { buffer.close() } catch (_: Throwable) {}
                            finish()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (request.get() == id) bitmap = null
                        finish()
                    }
                },
            )
        } catch (_: Throwable) {
            bitmap = null
            main.post { finish() }
        }
    }

    /** The screenshot taken by the last [take], or null if there is none. */
    fun shot(): Bitmap? = bitmap

    /** The app the last [take] found under the canvas, or null if it could not tell. */
    fun app(): PageContext? = app

    /** Forget the screenshot and the app, and any screenshot that is still on its way. */
    fun clear() {
        request.incrementAndGet()
        bitmap = null
        app = null
    }

    /**
     * The app under the canvas, from the accessibility service's window list: the active
     * application window, else the focused one, else the topmost. Only the window's title,
     * its root's package name and that package's label are read, never any text or content
     * in the window. Null when that window is Voice Relay's own, the system UI, or unknown.
     */
    private fun appUnder(service: AccessibilityService): PageContext? {
        val windows = try {
            service.windows
        } catch (_: Throwable) {
            return null
        }
        try {
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val window = apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused } ?: apps.firstOrNull()
                ?: return null
            val root = window.root ?: return null
            val pkg = try {
                root.packageName?.toString()
            } finally {
                if (Build.VERSION.SDK_INT < 33) {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
            if (pkg.isNullOrBlank() || pkg == service.packageName || pkg == SYSTEM_UI) return null
            val label = try {
                val pm = service.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                null // not visible to this app (no launcher icon), or gone
            }
            return PageContext.of(label, pkg, window.title)
        } catch (_: Throwable) {
            return null
        } finally {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                windows.forEach { try { it.recycle() } catch (_: Throwable) {} }
            }
        }
    }

    private const val SYSTEM_UI = "com.android.systemui"
}
