package blbl.cat3399.core.ui

import android.app.Activity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object Immersive {
    fun apply(activity: Activity, enabled: Boolean) {
        val window = activity.window ?: return
        val fullscreen = enabled || WindowDisplayPolicy.isPhoneLandscapePlayback(activity)
        WindowDisplayPolicy.applyWindow(activity, fullscreen = fullscreen)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        WindowDisplayPolicy.requestApplyInsets(activity)
    }
}
