package com.projectlist.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {

    @Test
    fun bareIpGetsHttpAndDefaultPort() {
        assertEquals(
            "http://10.5.1.147:3000",
            ServerAddress.normalize("10.5.1.147"),
        )
    }

    @Test
    fun hostWithPortKeepsPortAndAddsHttp() {
        assertEquals(
            "http://10.5.1.147:8080",
            ServerAddress.normalize("10.5.1.147:8080"),
        )
    }

    @Test
    fun explicitHttpsKeepsSchemeAndGetsDefaultPort() {
        assertEquals(
            "https://project.local:3000",
            ServerAddress.normalize("https://project.local"),
        )
    }

    @Test
    fun explicitHttpsAndPortAreRespected() {
        assertEquals(
            "https://project.local:8443",
            ServerAddress.normalize("https://project.local:8443/"),
        )
    }

    @Test
    fun trailingSlashAndApiAreHandled() {
        assertEquals(
            "http://10.5.1.147:3000",
            ServerAddress.normalize("http://10.5.1.147:3000/api/"),
        )
    }

    @Test
    fun defaultSchemeAndPortCanBeOverridden() {
        assertEquals(
            "https://10.5.1.147:9443",
            ServerAddress.normalize("10.5.1.147", "https", 9443),
        )
    }

    @Test
    fun optionsOverrideSchemeAndPortFromText() {
        val address = ServerAddress.withOptions("https://10.5.1.147:8443", "http", 3000)
        assertEquals("http://10.5.1.147:3000", address.origin)
    }

    @Test
    fun ipv6InBracketsIsSupported() {
        assertEquals(
            "http://[fe80::1]:3000",
            ServerAddress.normalize("[fe80::1]:3000"),
        )
    }

    @Test
    fun hostWithPathKeepsPath() {
        assertEquals(
            "http://project.local:3000/projectlist",
            ServerAddress.normalize("project.local/projectlist"),
        )
    }

    @Test
    fun invalidPortIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerAddress.normalize("10.5.1.147:70000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerAddress.normalize("10.5.1.147:abc")
        }
    }

    @Test
    fun blankAddressIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerAddress.normalize("   ")
        }
    }

    @Test
    fun unsupportedSchemeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerAddress.normalize("ftp://10.5.1.147")
        }
    }
}
