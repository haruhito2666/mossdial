package com.mossdial.server

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A deadline for reading one whole request, not one read.
 *
 * `Socket.soTimeout` is per read: a client that sends a byte every fourteen seconds never trips a
 * fifteen second timeout, so it can hold a worker forever. This clock is armed with the remaining
 * budget before every read, which turns the timeout into a total bound on the request line,
 * headers and body.
 */
internal class RequestDeadline(
    timeoutMillis: Int,
    private val nanoTime: () -> Long = System::nanoTime
) {
    init {
        require(timeoutMillis > 0) { "A request deadline must be positive" }
    }

    private val deadlineNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())

    fun remainingNanos(): Long = deadlineNanos - nanoTime()

    /** Whole milliseconds left, or zero once the request has run out of time. */
    fun remainingMillis(): Int {
        val remaining = TimeUnit.NANOSECONDS.toMillis(remainingNanos())
        return if (remaining <= 0L) 0 else remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun expired(): Boolean = remainingNanos() <= 0L
}

/**
 * An input stream that arms the socket's read timeout with the smaller of its normal timeout and
 * the time left in [deadline] before each read. An exhausted budget is a [SocketTimeoutException],
 * which both listeners already answer as a timeout or a closed connection.
 */
internal class DeadlineInputStream(
    input: InputStream,
    private val deadline: RequestDeadline,
    private val armReadTimeout: (millis: Int) -> Unit
) : FilterInputStream(input) {

    override fun read(): Int {
        arm()
        return super.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        arm()
        return super.read(buffer, offset, length)
    }

    private fun arm() {
        val remaining = deadline.remainingMillis()
        if (remaining <= 0) {
            throw SocketTimeoutException("The request took too long")
        }
        armReadTimeout(remaining)
    }
}

/**
 * An output stream that closes its socket if a write makes no progress for [timeoutMillis].
 *
 * Java sockets have no write timeout. A client that requests a large file and then stops reading
 * fills the kernel send buffer, and the worker blocks in `write` forever. Every successful write
 * or flush re-arms this timer, so a slow but progressing download is never interrupted while a
 * stalled one is released. The watchdog is shared because one daemon thread is enough for every
 * response in the process.
 */
internal class WatchdogOutputStream(
    private val socket: Socket,
    output: OutputStream,
    private val timeoutMillis: Long
) : FilterOutputStream(output) {

    private var task: ScheduledFuture<*>? = null

    override fun write(value: Int) {
        arm()
        out.write(value)
        arm()
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        arm()
        out.write(buffer, offset, length)
        arm()
    }

    override fun flush() {
        arm()
        out.flush()
        arm()
    }

    /** Cancels the watchdog once the response has been written completely. */
    fun disarm() {
        task?.cancel(false)
        task = null
    }

    override fun close() {
        // The socket is owned by the request handler, not by this wrapper.
        disarm()
    }

    private fun arm() {
        task?.cancel(false)
        task = WATCHDOG.schedule(
            { runCatching { socket.close() } },
            timeoutMillis,
            TimeUnit.MILLISECONDS
        )
    }

    private companion object {
        val WATCHDOG: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "MossdialWriteWatchdog").apply { isDaemon = true }
            }
    }
}
