package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileNamesTest {

    @Test
    fun displayNameIsTheNameElseTheServer() {
        assertEquals("Work", ProfileNames.display("  Work ", "vpn.example.com"))
        assertEquals("vpn.example.com", ProfileNames.display(" ", "vpn.example.com"))
        assertNull(ProfileNames.display("", null))
        assertNull(ProfileNames.display("", " "))
    }

    @Test
    fun copyAddsANumber() {
        assertEquals("Travel NL 2", ProfileNames.copy("Travel NL", listOf("Travel NL")))
        assertEquals("vpn.example.com 2", ProfileNames.copy("vpn.example.com", emptyList()))
    }

    @Test
    fun copySkipsNamesInUse() {
        assertEquals("Work 3", ProfileNames.copy("Work", listOf("Work", "work 2")))
        assertEquals("Work 3", ProfileNames.copy("Work 2", listOf("Work", "Work 2")))
        assertEquals("Work 4", ProfileNames.copy("Work 2", listOf("Work 3")))
    }

    @Test
    fun initialIsTheFirstLetterOrDigit() {
        assertEquals("W", ProfileNames.initial("work"))
        assertEquals("V", ProfileNames.initial("vpn.example.com"))
        assertEquals("2", ProfileNames.initial("  2nd office"))
        assertEquals("Ж", ProfileNames.initial("(жк)"))
        assertEquals("#", ProfileNames.initial("…"))
    }
}
