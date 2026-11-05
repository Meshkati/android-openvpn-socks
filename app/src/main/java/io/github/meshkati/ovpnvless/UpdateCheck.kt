package io.github.meshkati.ovpnvless

import engine.Engine
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/**
 * Asks GitHub for the tag of the latest release, e.g. "v0.2.0". Sends nothing
 * about the user or the phone, only [userAgent], which GitHub requires.
 * With VLESS connected it goes through Xray, like apps' traffic ([Updates.way]).
 * Blocks: call it off the main thread. Null if the request fails, or can't be
 * made without the real IP showing (a VLESS session connecting).
 */
fun fetchLatestRelease(userAgent: String): String? {
    val headers = mapOf(
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to userAgent,
    )
    val body = when (Updates.way(VpnState.phase, VpnState.route)) {
        Updates.Way.DIRECT -> fetchDirect(headers)
        Updates.Way.XRAY -> try {
            Engine.fetch(Updates.LATEST_URL, headers.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        } catch (e: Exception) {
            null // the engine stopped meanwhile, or GitHub didn't answer
        }
        null -> null
    } ?: return null
    return try {
        JSONObject(body).optString("tag_name").takeIf { it.isNotEmpty() }
    } catch (e: JSONException) {
        null
    }
}

/** The answer's body from a request made by the app itself, or null. */
private fun fetchDirect(headers: Map<String, String>): String? {
    val conn = URI(Updates.LATEST_URL).toURL().openConnection() as HttpsURLConnection
    return try {
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.useCaches = false
        for ((name, value) in headers) conn.setRequestProperty(name, value)
        if (conn.responseCode != HttpsURLConnection.HTTP_OK) return null
        conn.inputStream.bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        null
    } finally {
        conn.disconnect()
    }
}
