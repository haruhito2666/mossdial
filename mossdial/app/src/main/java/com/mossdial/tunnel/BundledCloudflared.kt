package com.mossdial.tunnel

import android.content.Context
import java.io.File

/**
 * The `cloudflared` executable that ships with the app.
 *
 * Android does not reliably allow an app to execute a file it downloaded into its own data
 * directory. A binary packaged as a JNI library is extracted into the app's native-library
 * directory, which is executable, so the tunnel can run without ADB, `chmod`, or a user-supplied
 * path. The build downloads the pinned upstream binary as `libcloudflared.so`.
 */
object BundledCloudflared {
    const val VERSION = "2026.10.6"
    const val LIBRARY_NAME = "libcloudflared.so"

    /** The executable path, or null when this build or device does not have the bundled engine. */
    fun path(context: Context): String? {
        val directory = context.applicationInfo.nativeLibraryDir ?: return null
        val executable = File(directory, LIBRARY_NAME)
        return executable
            .takeIf { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    fun isAvailable(context: Context): Boolean = path(context) != null
}
