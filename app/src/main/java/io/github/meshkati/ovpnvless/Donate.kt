package io.github.meshkati.ovpnvless

import java.math.BigDecimal

/**
 * Donations through PayPal.me. The app only opens the link in the browser:
 * it never sees or keeps anything about the payment.
 */
object Donate {
    const val PAYPAL = "https://paypal.me/meshkati"
    const val CURRENCY = "USD"
    const val COFFEE = "2.99"
    const val LUNCH = "9.99"
    private val MAX = BigDecimal(10_000)

    private val AMOUNT = Regex("""\d{1,5}(\.\d{1,2})?""")

    /**
     * A typed amount as PayPal.me takes it ("5", "7.5"), or null if it isn't
     * one. Accepts "$5", "5,50" and Persian or Arabic digits.
     */
    fun amount(text: String): String? {
        val s = buildString {
            for (c in text.trim().removePrefix("$").trim()) {
                append(
                    when (c) {
                        in '۰'..'۹' -> '0' + (c - '۰')
                        in '٠'..'٩' -> '0' + (c - '٠')
                        ',', '٫' -> '.'
                        else -> c
                    }
                )
            }
        }
        if (!AMOUNT.matches(s)) return null
        val value = BigDecimal(s)
        if (value.signum() <= 0 || value > MAX) return null
        return value.stripTrailingZeros().toPlainString()
    }

    /** e.g. https://paypal.me/meshkati/2.99USD */
    fun url(amount: String) = "$PAYPAL/$amount$CURRENCY"
}
