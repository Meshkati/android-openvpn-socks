package io.github.meshkati.ovpnvless

import java.io.InputStream

/** The first [limit] bytes of [input], or all of it if shorter (InputStream.readNBytes needs API 33). */
fun readAtMost(input: InputStream, limit: Int): ByteArray {
    val buf = ByteArray(limit)
    var n = 0
    while (n < limit) {
        val r = input.read(buf, n, limit - n)
        if (r < 0) break
        n += r
    }
    return buf.copyOf(n)
}
