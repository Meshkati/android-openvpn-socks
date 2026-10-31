package io.github.meshkati.ovpnvless

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The OpenVPN and Xray log, full screen, with a source filter. */
class LogActivity : Activity() {

    private enum class Filter { ALL, OPENVPN, XRAY }

    private lateinit var scroll: ScrollView
    private lateinit var logText: TextView
    private lateinit var empty: View
    private lateinit var chips: Map<Filter, View>
    private var filter = Filter.ALL
    private var shown = ""
    private val observer = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        edgeToEdge(findViewById(R.id.root))

        scroll = findViewById(R.id.log_scroll)
        logText = findViewById(R.id.log_text)
        empty = findViewById(R.id.log_empty)
        chips = mapOf(
            Filter.ALL to findViewById(R.id.filter_all),
            Filter.OPENVPN to findViewById(R.id.filter_openvpn),
            Filter.XRAY to findViewById(R.id.filter_xray),
        )
        for ((f, chip) in chips) chip.setOnClickListener { setFilter(f) }
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.copy).setOnClickListener { copy() }
        setFilter(Filter.ALL)
    }

    override fun onStart() {
        super.onStart()
        VpnState.addObserver(observer)
    }

    override fun onStop() {
        VpnState.removeObserver(observer)
        super.onStop()
    }

    private fun setFilter(f: Filter) {
        filter = f
        for ((key, chip) in chips) chip.isSelected = key == f
        shown = ""
        render(force = true)
    }

    private fun lines(): List<String> = VpnState.logLines().filter {
        when (filter) {
            Filter.ALL -> true
            Filter.OPENVPN -> LogLines.source(it) == LogLines.Source.OPENVPN
            Filter.XRAY -> LogLines.source(it) == LogLines.Source.XRAY
        }
    }

    private fun render(force: Boolean = false) {
        // Replacing the text would drop a selection the user is making.
        if (!force && logText.hasSelection()) return
        val lines = lines()
        val text = lines.joinToString("\n")
        if (text == shown && !force) return
        shown = text
        empty.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE

        val atEnd = force || !scroll.canScrollVertically(1)
        val out = SpannableStringBuilder()
        for ((i, line) in lines.withIndex()) {
            if (i > 0) out.append('\n')
            val start = out.length
            out.append(line)
            val color = when {
                LogLines.isError(line) -> R.color.log_error
                else -> when (LogLines.source(line)) {
                    LogLines.Source.OPENVPN -> R.color.log_openvpn
                    LogLines.Source.XRAY -> R.color.log_xray
                    LogLines.Source.APP -> R.color.log_app
                }
            }
            out.setSpan(ForegroundColorSpan(getColor(color)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        logText.text = out
        if (atEnd) scroll.post { scroll.scrollTo(0, logText.height) }
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.log), shown))
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }
}
