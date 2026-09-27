package com.mossdial.server

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.io.File

object PathPolicy {
    fun resolve(root: File, rawPath: String): File? {
        val decoded = decode(rawPath) ?: return null
        if (!decoded.startsWith("/") || decoded.indexOf('\u0000') >= 0 || '\\' in decoded) return null
        val segments = decoded.split('/')
        if (segments.any { it == ".." }) return null
        val relative = decoded.removePrefix("/")
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, relative).canonicalFile
        if (candidate != canonicalRoot && !candidate.path.startsWith(canonicalRoot.path + File.separator)) return null
        return candidate
    }

    private fun decode(value: String): String? {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '%') {
                if (character.code > 0x7f) return null
                bytes.write(character.code)
                index++
                continue
            }
            if (index + 2 >= value.length) return null
            val high = hex(value[index + 1]) ?: return null
            val low = hex(value[index + 2]) ?: return null
            bytes.write((high shl 4) or low)
            index += 3
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun hex(character: Char): Int? = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> null
    }
}
