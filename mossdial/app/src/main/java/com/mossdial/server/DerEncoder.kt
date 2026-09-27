package com.mossdial.server

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object DerEncoder {
    const val SEQUENCE = 0x30
    const val SET = 0x31
    const val INTEGER = 0x02
    const val BIT_STRING = 0x03
    const val OCTET_STRING = 0x04
    const val NULL = 0x05
    const val OID = 0x06
    const val UTF8_STRING = 0x0C
    const val PRINTABLE_STRING = 0x13
    const val IA5_STRING = 0x16
    const val UTC_TIME = 0x17

    fun value(tag: Int, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(tag)
        writeLength(output, content.size)
        output.write(content)
        return output.toByteArray()
    }

    fun sequence(vararg values: ByteArray): ByteArray = value(SEQUENCE, concat(*values))

    fun set(vararg values: ByteArray): ByteArray = value(SET, concat(*values))

    fun integer(value: BigInteger): ByteArray {
        val source = value.toByteArray()
        val normalized = if (source.size > 1 && source[0] == 0.toByte() && source[1] < 0) source else source
        return value(INTEGER, normalized)
    }

    fun integer(value: Int): ByteArray = integer(BigInteger.valueOf(value.toLong()))

    fun oid(value: String): ByteArray = value(OID, encodeOid(value))

    fun utf8(value: String): ByteArray = value(UTF8_STRING, value.toByteArray(StandardCharsets.UTF_8))

    fun printable(value: String): ByteArray = value(PRINTABLE_STRING, value.toByteArray(StandardCharsets.US_ASCII))

    fun ia5(value: String): ByteArray = value(IA5_STRING, value.toByteArray(StandardCharsets.US_ASCII))

    fun utcTime(value: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return value(UTC_TIME, format.format(value).toByteArray(StandardCharsets.US_ASCII))
    }

    fun nullValue(): ByteArray = value(NULL, byteArrayOf())

    fun bitString(value: ByteArray, unusedBits: Int = 0): ByteArray =
        value(BIT_STRING, byteArrayOf(unusedBits.toByte()) + value)

    fun octetString(value: ByteArray): ByteArray = value(OCTET_STRING, value)

    fun concat(vararg values: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        values.forEach(output::write)
        return output.toByteArray()
    }

    private fun writeLength(output: ByteArrayOutputStream, length: Int) {
        if (length < 128) {
            output.write(length)
            return
        }
        val bytes = ArrayList<Byte>()
        var remaining = length
        while (remaining > 0) {
            bytes.add(0, (remaining and 0xff).toByte())
            remaining = remaining ushr 8
        }
        output.write(0x80 or bytes.size)
        bytes.forEach { output.write(it.toInt()) }
    }

    private fun encodeOid(value: String): ByteArray {
        val parts = value.split('.').map { it.toLong() }
        require(parts.size >= 2) { "Invalid OID" }
        val output = ByteArrayOutputStream()
        writeBase128(output, parts[0] * 40 + parts[1])
        parts.drop(2).forEach { writeBase128(output, it) }
        return output.toByteArray()
    }

    private fun writeBase128(output: ByteArrayOutputStream, input: Long) {
        val bytes = ArrayList<Byte>()
        var remaining = input
        do {
            bytes.add(0, (remaining and 0x7f).toByte())
            remaining = remaining ushr 7
        } while (remaining > 0)
        bytes.forEachIndexed { index, byte ->
            output.write(if (index == bytes.size - 1) byte.toInt() and 0x7f else (byte.toInt() and 0x7f) or 0x80)
        }
    }
}
