package io.github.meshkati.ovpnvless

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** VLESS server: the share link or Xray JSON, with a read-only summary. */
class VlessActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var vless: EditText
    private lateinit var error: TextView
    private lateinit var summary: View
    private lateinit var summaryCaption: TextView
    private lateinit var summaryRows: LinearLayout
    private lateinit var help: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vless)
        edgeToEdge(findViewById(R.id.root))
        settings = Settings(this)

        vless = findViewById(R.id.vless)
        error = findViewById(R.id.vless_error)
        summary = findViewById(R.id.summary)
        summaryCaption = findViewById(R.id.summary_caption)
        summaryRows = findViewById(R.id.summary_rows)
        help = findViewById(R.id.vless_help)

        vless.setText(settings.vless)
        vless.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.paste).setOnClickListener { paste() }
        render()
    }

    override fun onPause() {
        settings.vless = vless.text.toString().trim()
        super.onPause()
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        vless.setText(text)
        vless.setSelection(vless.length())
    }

    private fun render() {
        val config = VlessConfig.parse(vless.text.toString())
        error.visibility = if (config == VlessConfig.Invalid) View.VISIBLE else View.GONE
        summaryRows.removeAllViews()
        when (config) {
            is VlessConfig.Link -> {
                summaryCaption.setText(R.string.vless_from_link)
                addRow(R.string.row_server, config.host)
                addRow(R.string.row_port, config.port.toString())
                addRow(R.string.row_transport, listOfNotNull(config.transport, config.path).joinToString(" · "))
                addRow(R.string.row_security, config.security)
                config.flow?.let { addRow(R.string.row_flow, it) }
                config.sni?.let { addRow(R.string.row_sni, it) }
                config.name?.let { addRow(R.string.row_name, it) }
                summary.visibility = View.VISIBLE
                help.setText(R.string.vless_help)
            }
            VlessConfig.Json -> {
                summaryCaption.setText(R.string.vless_custom)
                summary.visibility = View.GONE
                help.setText(R.string.vless_custom_help)
            }
            else -> {
                summary.visibility = View.GONE
                help.setText(R.string.vless_help)
            }
        }
    }

    private fun addRow(key: Int, value: String) {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * dp).toInt(), (7 * dp).toInt(), (16 * dp).toInt(), (7 * dp).toInt())
        }
        row.addView(TextView(this).apply {
            setText(key)
            textSize = 14f
            setTextColor(getColor(R.color.muted))
        })
        row.addView(TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(getColor(R.color.ink))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding((16 * dp).toInt(), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summaryRows.addView(row)
    }
}
