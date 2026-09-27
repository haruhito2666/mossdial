package com.mossdial.aiapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bearer credential: how it is produced, how a header is taken apart, and how a guess is
 * compared against the stored value.
 */
class AiApiTokenTest {

    @Test
    fun generatesAUrlSafeTokenOfAFixedLength() {
        val token = AiApiToken.generate()

        assertEquals(43, token.length)
        assertTrue(AiApiToken.isValid(token))
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse("=" in token)
    }

    @Test
    fun generatesADifferentTokenEveryTime() {
        val tokens = (1..64).map { AiApiToken.generate() }.toSet()

        assertEquals(64, tokens.size)
        assertNotEquals(AiApiToken.generate(), AiApiToken.generate())
    }

    @Test
    fun refusesAnythingThatIsNotATokenThisObjectCouldHaveMade() {
        assertFalse(AiApiToken.isValid(""))
        assertFalse(AiApiToken.isValid("short"))
        assertFalse(AiApiToken.isValid("x".repeat(AiApiToken.MIN_LENGTH - 1)))
        assertFalse(AiApiToken.isValid("x".repeat(AiApiToken.MAX_LENGTH + 1)))
        assertFalse(AiApiToken.isValid("x".repeat(40) + " "))
        assertFalse(AiApiToken.isValid("x".repeat(40) + "="))
        assertFalse(AiApiToken.isValid("x".repeat(40) + "\n"))
        assertFalse(AiApiToken.isValid("é".repeat(40)))
        assertTrue(AiApiToken.isValid("x".repeat(40)))
    }

    @Test
    fun takesTheTokenOutOfABearerHeader() {
        val token = AiApiToken.generate()

        assertEquals(token, AiApiToken.bearerToken("Bearer $token"))
        assertEquals(token, AiApiToken.bearerToken("bearer $token"))
        assertEquals(token, AiApiToken.bearerToken("BEARER $token"))
        assertEquals(token, AiApiToken.bearerToken("  Bearer   $token  "))
    }

    @Test
    fun refusesAHeaderThatIsNotABearerOne() {
        assertNull(AiApiToken.bearerToken(null))
        assertNull(AiApiToken.bearerToken(""))
        assertNull(AiApiToken.bearerToken("   "))
        assertNull(AiApiToken.bearerToken("Bearer"))
        assertNull(AiApiToken.bearerToken("Bearer "))
        assertNull(AiApiToken.bearerToken("Basic dXNlcjpwYXNz"))
        assertNull(AiApiToken.bearerToken("Token abc"))
        assertNull(AiApiToken.bearerToken("Bearerabc"))
        // Optional whitespace around the value is legal HTTP, and a value with a space in it is
        // carried through as-is so the comparison is what refuses it.
        assertEquals("abc def", AiApiToken.bearerToken("Bearer  abc def "))
    }

    @Test
    fun acceptsOnlyTheStoredToken() {
        val token = AiApiToken.generate()

        assertTrue(AiApiToken.matches(token, "Bearer $token"))
        assertTrue(AiApiToken.matches(token, "bearer $token"))
    }

    @Test
    fun refusesEveryOtherGuess() {
        val token = AiApiToken.generate()

        assertFalse(AiApiToken.matches(token, "Bearer ${token.dropLast(1)}x"))
        assertFalse(AiApiToken.matches(token, "Bearer ${token.dropLast(1)}"))
        assertFalse(AiApiToken.matches(token, "Bearer ${token.drop(1)}"))
        assertFalse(AiApiToken.matches(token, "Bearer " + token.drop(1) + "x"))
        assertFalse(AiApiToken.matches(token, "Bearer " + token + token))
        assertFalse(AiApiToken.matches(token, "Bearer abc def"))
        assertFalse(AiApiToken.matches(token, "Bearer"))
        assertFalse(AiApiToken.matches(token, "bearer "))
        assertFalse(AiApiToken.matches(token, "Basic $token"))
        assertFalse(AiApiToken.matches(token, token))
        assertFalse(AiApiToken.matches(token, null))
        assertFalse(AiApiToken.matches(token, ""))
    }

    @Test
    fun refusesEverythingWhenNoTokenIsStored() {
        assertFalse(AiApiToken.matches("", "Bearer anything"))
        assertFalse(AiApiToken.matches("", null))
    }

    @Test
    fun comparesTheWholeValueSoNoPrefixIsEnough() {
        val token = AiApiToken.generate()
        val shared = token.take(20)

        assertEquals(20, shared.length)
        assertFalse(AiApiToken.matches(token, "Bearer $shared"))
        assertNotEquals(shared, token)
    }
}
