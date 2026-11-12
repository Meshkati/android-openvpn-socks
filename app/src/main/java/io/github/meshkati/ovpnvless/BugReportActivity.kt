package io.github.meshkati.ovpnvless

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Report a problem, from the home screen's bug icon. The report ([BugReport])
 * is taken when the screen opens, so it shows the app as it was when the
 * problem happened, e.g. stuck on Connected. The user adds what happened,
 * reads the report, and sends it: a prefilled GitHub issue, or any app
 * through the share sheet. The app sends nothing itself.
 */
class BugReportActivity : Activity() {

    private lateinit var description: EditText
    private lateinit var snapshot: BugReport.Snapshot
    private lateinit var secrets: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bug_report)
        edgeToEdge(findViewById(R.id.root))

        // Kept across a rotation: taken again, it would no longer show the moment the user came here.
        snapshot = retained() ?: Diagnostics.snapshot(this)
        secrets = Diagnostics.secrets(this)

        description = findViewById(R.id.bug_description)
        val taken = DateUtils.formatDateTime(this, snapshot.time, DateUtils.FORMAT_SHOW_TIME)
        findViewById<TextView>(R.id.bug_preview_label).text = getString(R.string.bug_preview_label, taken)
        findViewById<TextView>(R.id.bug_preview).text = BugReport.body(snapshot, "", secrets)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.copy).setOnClickListener { copy() }
        findViewById<View>(R.id.bug_share).setOnClickListener { share() }
        findViewById<View>(R.id.bug_github).setOnClickListener { openIssue() }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any = snapshot

    @Suppress("DEPRECATION")
    private fun retained() = lastNonConfigurationInstance as? BugReport.Snapshot

    private fun what() = description.text.toString()

    private fun title() = BugReport.title(what(), snapshot.device.app)

    private fun fullReport() = BugReport.body(snapshot, what(), secrets)

    private fun copy() {
        copySensitive(this, getString(R.string.bug_title), fullReport())
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.bug_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title())
            .putExtra(Intent.EXTRA_TEXT, fullReport())
        startActivity(Intent.createChooser(send, getString(R.string.crash_share)))
    }

    /** A link holds only so much: if the report is cut, the full one goes on the clipboard to paste. */
    private fun openIssue() {
        val issue = BugReport.issue(title(), snapshot, what(), secrets, getString(R.string.bug_cut_note))
        if (!issue.complete) {
            copySensitive(this, getString(R.string.bug_title), fullReport())
            Toast.makeText(this, R.string.bug_cut_toast, Toast.LENGTH_LONG).show()
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(issue.url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.update_no_browser, CrashReport.ISSUE_URL), Toast.LENGTH_LONG).show()
        }
    }
}
