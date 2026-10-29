package com.mossdial.server

import java.io.ByteArrayInputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestDeadlineTest {

    @Test
    fun `the deadline counts down from the moment it is created`() {
        var now = 0L
        val deadline = RequestDeadline(1_000) { now }

        assertEquals(1_000, deadline.remainingMillis())
        assertFalse(deadline.expired())

        now = 400_000_000L
        assertEquals(600, deadline.remainingMillis())

        now = 1_000_000_000L
        assertEquals(0, deadline.remainingMillis())
        assertTrue(deadline.expired())
    }

    @Test
    fun `a read after the deadline is refused instead of blocking again`() {
        var now = 0L
        val deadline = RequestDeadline(1_000) { now }
        val input = DeadlineInputStream(
            input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            deadline = deadline,
            armReadTimeout = { }
        )

        assertEquals(1, input.read())
        now = 1_000_000_000L

        assertThrows(SocketTimeoutException::class.java) { input.read() }
    }

    @Test
    fun `each read gets the time remaining, not the original timeout`() {
        var now = 0L
        val armed = mutableListOf<Int>()
        val deadline = RequestDeadline(5_000) { now }
        val input = DeadlineInputStream(
            input = ByteArrayInputStream(ByteArray(3)),
            deadline = deadline,
            armReadTimeout = { armed += it }
        )

        input.read()
        now = 2_000_000_000L
        input.read()

        assertEquals(listOf(5_000, 3_000), armed)
    }
}
