package com.mossdial.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/**
 * Fetches a model over HTTPS with [HttpURLConnection] and stores it in the model
 * directory.
 *
 * The connection factory is injectable so the protocol handling can be tested on
 * the JVM. Redirects are followed by [HttpURLConnection] and the final URL is
 * re-checked, so a redirect to plain HTTP is rejected instead of silently accepted.
 */
class ModelDownloader(
    private val store: ModelStore,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.asHttpConnection() }
) {

    class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Downloads [url] and returns the stored model. [onProgress] is called with the
     * bytes written so far and the expected total, or -1 when the server does not
     * send a content length.
     */
    fun download(url: String, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }): StoredModel {
        val target = parseHttpsUrl(url)
        val kind = ModelKind.of(target.path.substringBefore('?'))
            ?: throw DownloadException("The download URL does not end in .gguf or .onnx")

        val directory = store.ensureDirectory()
        val name = ModelStore.sanitizeName(target.path.substringAfterLast('/').substringBefore('?'))
            ?: throw DownloadException("The download URL has no usable file name")
        val destination = uniqueTarget(directory, name)
        val temporary = File(directory, destination.name + PART_SUFFIX)

        var connection: HttpURLConnection? = null
        try {
            connection = connectionFactory(target).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "Mossdial")
            }
            connection.connect()

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw DownloadException("The server answered $status for $name")
            }
            if (connection.url.protocol.lowercase() != "https") {
                throw DownloadException("The download was redirected off HTTPS and was stopped")
            }

            val declared = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            if (declared > ModelStore.MAX_MODEL_BYTES) {
                throw DownloadException("$name is larger than the ${ModelStore.MAX_MODEL_BYTES} byte limit")
            }

            var written = 0L
            onProgress(0L, declared)
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        written += read
                        if (written > ModelStore.MAX_MODEL_BYTES) {
                            throw DownloadException("The download exceeded the size limit and was stopped")
                        }
                        output.write(buffer, 0, read)
                        onProgress(written, declared)
                    }
                    output.flush()
                }
            }

            if (written == 0L) {
                throw DownloadException("The server sent an empty response for $name")
            }
            if (kind == ModelKind.GGUF && !hasGgufMagic(temporary)) {
                throw DownloadException("$name is not a GGUF file")
            }
            if (declared > 0L && written != declared) {
                throw DownloadException("The download of $name ended early ($written of $declared bytes)")
            }
            if (!temporary.renameTo(destination)) {
                throw DownloadException("Unable to store the download as ${destination.name}")
            }
            onProgress(written, if (declared > 0L) declared else written)
            return StoredModel(destination.name, destination.length(), kind, directory)
        } catch (failure: Throwable) {
            temporary.delete()
            throw if (failure is IOException) failure else DownloadException("Download failed: $name", failure)
        } finally {
            connection?.disconnect()
        }
    }

    /** Rejects anything that is not plain HTTPS before a socket is opened. */
    private fun parseHttpsUrl(url: String): URL {
        val parsed = try {
            URL(url)
        } catch (failure: IOException) {
            throw DownloadException("Not a valid URL: $url", failure)
        }
        if (parsed.protocol.lowercase() != "https") {
            throw DownloadException("Only https:// downloads are allowed")
        }
        if (parsed.host.isBlank()) {
            throw DownloadException("The download URL has no host")
        }
        return parsed
    }

    private fun uniqueTarget(directory: File, name: String): File {
        var candidate = File(directory, name)
        var suffix = 2
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        while (candidate.exists() || File(directory, candidate.name + PART_SUFFIX).exists()) {
            candidate = File(directory, "$stem-$suffix.$extension")
            suffix++
            if (suffix > 999) {
                throw DownloadException("Too many models named $name")
            }
        }
        return candidate
    }

    private fun hasGgufMagic(file: File): Boolean = try {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == GGUF_MAGIC
        }
    } catch (_: IOException) {
        false
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val PART_SUFFIX = ".part"
        private const val GGUF_MAGIC = "GGUF"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}

/** The only conversion this downloader needs from [URLConnection]. */
fun URL.asHttpConnection(): HttpURLConnection = openConnection() as HttpURLConnection
