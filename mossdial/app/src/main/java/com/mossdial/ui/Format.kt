package com.mossdial.ui

import java.util.Locale

/**
 * Bytes as something a person can read.
 *
 * This existed twice with two different unit systems: `MainActivity` said KiB/MiB/GiB and
 * `FileManagerScreen` said KB/MB/GB for the same numbers, so the same file was one size on one
 * screen and a different size on another. There is now one definition and one unit system.
 *
 * Binary units are used because they are the ones the underlying counts are actually made of: the
 * server counts bytes it has written and the file manager reports file sizes. Dividing by 1024
 * keeps those measurements consistent.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}