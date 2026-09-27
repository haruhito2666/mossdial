package com.mossdial.data

import com.mossdial.aiapi.AiApiToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Comparator
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** In-memory preferences, so the port rules are exercised without an Android runtime. */
private class ApiPreferenceStore(private val values: MutableMap<String, Any> = mutableMapOf()) :
    PreferenceStore {
    override fun getBoolean(key: String, fallback: Boolean): Boolean = values[key] as? Boolean ?: fallback
    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getInt(key: String, fallback: Int): Int = values[key] as? Int ?: fallback
    override fun putInt(key: String, value: Int) {
        values[key] = value
    }

    override fun getString(key: String, fallback: String): String = values[key] as? String ?: fallback
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

class AiApiSettingsTest {
    private lateinit var directory: File
    private lateinit var secrets: EncryptedSecretStore
    private lateinit var preferences: ApiPreferenceStore
    private lateinit var key: SecretKey

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("mossdial-aiapi-").toFile()
        key = KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
        secrets = EncryptedSecretStore(File(directory, "secrets"), { key })
        preferences = ApiPreferenceStore()
    }

    @After
    fun tearDown() {
        if (!directory.exists()) return
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    @Test
    fun defaultsToItsOwnPortOnLoopbackWithNoToken() {
        val settings = settings()

        assertEquals(8081, settings.port)
        assertEquals(8080, ServerSettingsRules.DEFAULT_PORT)
        assertFalse(settings.allowLan)
        assertEquals("127.0.0.1", settings.bindAddress())
        assertFalse(settings.hasToken())
        assertNull(settings.token())
    }

    @Test
    fun persistsThePortAndTheLanChoice() {
        settings().apply {
            port = 9_001
            allowLan = true
        }

        val reopened = settings()
        assertEquals(9_001, reopened.port)
        assertTrue(reopened.allowLan)
        assertEquals("0.0.0.0", reopened.bindAddress())
    }

    @Test
    fun normalisesAPortOnTheWayInAndOnTheWayOut() {
        settings().port = 80
        assertEquals(1024, settings().port)

        settings().port = 70_000
        assertEquals(65535, settings().port)

        val hostile = AiApiSettings(ApiPreferenceStore(mutableMapOf("port" to 1)), secrets)
        assertEquals(1024, hostile.port)
    }

    @Test
    fun storesTheTokenEncryptedUnderItsOwnSlot() {
        val token = AiApiToken.generate()
        val settings = settings()

        settings.storeToken(token)

        assertTrue(settings.hasToken())
        assertEquals(token, settings.token())
        assertTrue(AiApiToken.isValid(token!!))
        // The blob on disk is an envelope, not the token.
        val blob = File(File(directory, "secrets"), AiApiSettings.TOKEN_SLOT).readBytes()
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(token))
    }

    @Test
    fun keepsTheApiCredentialAwayFromTheTunnelCredential() {
        assertNotEquals(AiApiSettings.TOKEN_SLOT, TunnelSettings.TOKEN_SLOT)
        assertNotEquals(AiApiSettings.KEY_ALIAS, AndroidKeystoreKeyProvider.DEFAULT_ALIAS)
    }

    @Test
    fun rotatingTheTokenRetiresTheStoredOne() {
        val settings = settings()
        val first = AiApiToken.generate()
        val second = AiApiToken.generate()

        settings.rotateToken(first)
        settings.rotateToken(second)

        assertEquals(second, settings.token())
        assertNotEquals(first, settings.token())

        settings.clearToken()
        assertFalse(settings.hasToken())
        assertNull(settings.token())
    }

    @Test
    fun refusesATokenThatIsNotOne() {
        val settings = settings()

        assertThrows(IllegalArgumentException::class.java) { settings.storeToken("") }
        assertThrows(IllegalArgumentException::class.java) { settings.storeToken("   ") }
        assertThrows(IllegalArgumentException::class.java) {
            settings.storeToken("x".repeat(AiApiSettings.MAX_TOKEN_LENGTH + 1))
        }
        assertFalse(settings.hasToken())
    }

    @Test
    fun reportsAbsenceWhenTheStoredCopyCannotBeOpened() {
        val settings = settings()
        settings.storeToken(AiApiToken.generate())
        val file = File(File(directory, "secrets"), AiApiSettings.TOKEN_SLOT)
        file.writeBytes(ByteArray(8) { 0x41 })

        assertNull(settings.token())
        assertFalse(file.exists())
    }

    private fun settings(): AiApiSettings = AiApiSettings(preferences, secrets)
}
