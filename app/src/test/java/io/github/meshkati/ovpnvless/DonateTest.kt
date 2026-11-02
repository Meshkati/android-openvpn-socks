package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DonateTest {

    @Test
    fun presetLinks() {
        assertEquals("https://paypal.me/Meshkati/3USD", Donate.url(Donate.COFFEE))
        assertEquals("https://paypal.me/Meshkati/10USD", Donate.url(Donate.LUNCH))
    }

    @Test
    fun typedAmounts() {
        assertEquals("5", Donate.amount("5"))
        assertEquals("5", Donate.amount(" \$5.00 "))
        assertEquals("7.5", Donate.amount("7,50"))
        assertEquals("12.25", Donate.amount("12.25"))
        assertEquals("10000", Donate.amount("10000"))
    }

    @Test
    fun persianAndArabicDigits() {
        assertEquals("25", Donate.amount("۲۵"))
        assertEquals("2.5", Donate.amount("۲٫۵"))
        assertEquals("40", Donate.amount("٤٠"))
    }

    @Test
    fun badAmounts() {
        for (s in listOf("", "0", "0.00", "-5", "abc", "5.", ".5", "1.234", "10000.01", "100000", "5 USD", "1e3")) {
            assertNull(s, Donate.amount(s))
        }
    }
}
