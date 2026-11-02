package io.github.meshkati.ovpnvless

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Buy me a coffee: opens PayPal.me in the browser with the amount ([Donate]). */
class DonateActivity : Activity() {

    private lateinit var amount: EditText
    private lateinit var amountHelp: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_donate)
        edgeToEdge(findViewById(R.id.root))

        amount = findViewById(R.id.donate_amount)
        amountHelp = findViewById(R.id.donate_amount_help)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<Button>(R.id.donate_coffee).apply {
            text = getString(R.string.donate_coffee, Donate.COFFEE)
            setOnClickListener { open(Donate.COFFEE) }
        }
        findViewById<Button>(R.id.donate_lunch).apply {
            text = getString(R.string.donate_lunch, Donate.LUNCH)
            setOnClickListener { open(Donate.LUNCH) }
        }
        findViewById<View>(R.id.donate_custom).setOnClickListener { donateCustom() }
        amount.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                donateCustom()
                true
            } else {
                false
            }
        }
        amount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = showAmountError(false)
        })
    }

    private fun donateCustom() {
        val value = Donate.amount(amount.text.toString())
        if (value == null) {
            showAmountError(true)
            amount.requestFocus()
            return
        }
        open(value)
    }

    private fun open(value: String) {
        val url = Donate.url(value)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.update_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    private fun showAmountError(show: Boolean) {
        amount.setBackgroundResource(if (show) R.drawable.bg_field_error else R.drawable.bg_field)
        amountHelp.setText(if (show) R.string.donate_custom_invalid else R.string.donate_custom_help)
        amountHelp.setTextColor(getColor(if (show) R.color.err else R.color.muted))
    }
}
