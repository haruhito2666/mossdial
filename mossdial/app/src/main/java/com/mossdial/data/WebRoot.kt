package com.mossdial.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

object WebRoot {
    private const val RELATIVE_PATH = "sites/default"

    fun ensure(context: Context): File {
        val root = File(context.filesDir, RELATIVE_PATH)
        if (Files.isSymbolicLink(root.toPath())) {
            throw SecurityException("The web root must not be a symbolic link")
        }
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Unable to create the web root")
        }
        if (!root.isDirectory) {
            throw IOException("The web root is not a directory")
        }
        val index = File(root, "index.html")
        if (Files.isSymbolicLink(index.toPath())) {
            throw SecurityException("The default page must not be a symbolic link")
        }
        if (index.exists() && !index.isFile) {
            throw IOException("The default page is not a file")
        }
        if (!index.exists()) {
            val content = """
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Mossdial</title></head>
                <body><main><h1>Mossdial</h1><p>Your local web root is ready.</p></main></body>
                </html>
                """.trimIndent()
            try {
                Files.newOutputStream(
                    index.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                ).use { output ->
                    output.write(content.toByteArray(StandardCharsets.UTF_8))
                }
            } catch (_: FileAlreadyExistsException) {
            }
        }
        return root
    }
}
