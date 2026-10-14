package com.jjveleber.gallery

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.jjveleber.gallery.crash.CrashLog
import com.jjveleber.gallery.imaging.buildImageLoader

class GalleryApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val info = packageManager.getPackageInfo(packageName, 0)
        val crashLog = CrashLog(
            filesDir,
            "App ${info.versionName} (${info.longVersionCode})\n" +
                "${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        )
        crashLog.install()
        container = AppContainer(this, crashLog)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = buildImageLoader(context, container.animated)
}
