package com.mossdial.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PathPolicyTest {
    @Test
    fun resolvesPathInsideRoot() {
        val root = File(System.getProperty("java.io.tmpdir"), "mossdial-path-test")
        val result = PathPolicy.resolve(root, "/site/index.html")
        assertEquals(File(root, "site/index.html").canonicalFile, result?.canonicalFile)
    }

    @Test
    fun rejectsTraversalAfterDecoding() {
        val root = File(System.getProperty("java.io.tmpdir"), "mossdial-path-test")
        assertNull(PathPolicy.resolve(root, "/%2e%2e/secret"))
        assertNull(PathPolicy.resolve(root, "/site/../../secret"))
    }

    @Test
    fun rejectsBackslashAndNull() {
        val root = File(System.getProperty("java.io.tmpdir"), "mossdial-path-test")
        assertNull(PathPolicy.resolve(root, "/site\\secret"))
        assertNull(PathPolicy.resolve(root, "/site%00"))
    }
}
