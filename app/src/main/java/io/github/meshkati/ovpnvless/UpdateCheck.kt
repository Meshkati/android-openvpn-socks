package io.github.meshkati.ovpnvless

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/**
 * Asks GitHub for the tag of the latest release, e.g. "v0.2.0". Sends nothing
 * about the user or the phone, only [userAgent], which GitHub requires.
 * Blocks: call it off the main thread. Null if the request fails.
 */
fun fetchLatestRelease(userAgent: String): String? {
    val conn = URI(Updates.LATEST_URL).toURL().openConnection() as HttpsURLConnection
    return try {
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.useCaches = false
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", userAgent)
        if (conn.responseCode != HttpsURLConnection.HTTP_OK) return null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        JSONObject(body).optString("tag_name").takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        null
    } finally {
        conn.disconnect()
    }
}
