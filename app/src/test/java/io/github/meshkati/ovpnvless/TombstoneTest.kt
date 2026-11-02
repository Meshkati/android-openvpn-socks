package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TombstoneTest {

    /** Just enough of a protobuf writer to build tombstones. */
    private class Proto {
        private val out = ByteArrayOutputStream()

        private fun varint(v: Long) {
            var x = v
            while (x and 0x7fL.inv() != 0L) {
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }

        fun int(field: Int, v: Long) = apply { varint((field shl 3).toLong()); varint(v) }
        fun bytes(field: Int, b: ByteArray) = apply { varint((field shl 3 or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray())
        fun msg(field: Int, m: Proto) = bytes(field, m.build())
        fun build(): ByteArray = out.toByteArray()
    }

    private fun frame(relPc: Long, file: String, function: String = "", offset: Long = 0, buildId: String = "") =
        Proto().int(1, relPc).int(2, relPc + 0x7000000000).str(4, function).int(5, offset).str(6, file).str(8, buildId)

    private fun thread(id: Int, name: String, vararg frames: Proto) =
        Proto().int(1, id.toLong()).str(2, name).apply { frames.forEach { msg(4, it) } }

    private fun entry(id: Int, thread: Proto) = Proto().int(1, id.toLong()).msg(2, thread)

    @Test
    fun readsSignalAbortAndCrashingThread() {
        val tombstone = Proto()
            .int(5, 4000).int(6, 4012)
            .msg(10, Proto().int(1, 6).str(2, "SIGABRT").int(3, -1).str(4, "SI_TKILL"))
            .str(14, "assertion failed")
            .msg(16, entry(4000, thread(4000, "main", frame(0x10, "/system/lib64/libc.so"))))
            .msg(16, entry(4012, thread(4012, "openvpn3",
                frame(0x9d2a8, "/apex/com.android.runtime/lib64/bionic/libc.so", "abort", 164, "abc123"),
                frame(0x1a2b4, "/data/app/~~XyZ==/io.github.meshkati.ovpnvless-Q1w==/lib/arm64/libovpn3.so",
                    "openvpn::Foo::bar", 20),
            )))
            // Log buffers hold the app's log, with server names: never read.
            .msg(18, Proto().str(1, "main").str(2, "connecting to vpn.example.com"))
            .build()

        val text = Tombstone.describe(tombstone)!!
        assertEquals(
            """
            signal 6 (SIGABRT), code -1 (SI_TKILL)
            abort: assertion failed
            thread 4012 "openvpn3"
              #00 pc 000000000009d2a8  /apex/com.android.runtime/lib64/bionic/libc.so (abort+164) (BuildId: abc123)
              #01 pc 000000000001a2b4  libovpn3.so (openvpn::Foo::bar+20)
            """.trimIndent(),
            text,
        )
        assertFalse(text.contains("example.com"))
    }

    @Test
    fun faultAddress() {
        val tombstone = Proto()
            .msg(10, Proto().int(1, 11).str(2, "SIGSEGV").int(3, 1).str(4, "SEGV_MAPERR").int(8, 1).int(9, 0x18))
            .build()
        assertEquals("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x18", Tombstone.describe(tombstone))
    }

    @Test
    fun longBacktraceIsCut() {
        val frames = Array(Tombstone.MAX_FRAMES + 5) { frame(it.toLong(), "libx.so") }
        val text = Tombstone.describe(Proto().int(6, 1).msg(16, entry(1, thread(1, "t", *frames))).build())!!
        assertTrue(text.contains("#63 pc"))
        assertFalse(text.contains("#64 pc"))
        assertTrue(text.endsWith("… 5 more frames"))
    }

    @Test
    fun notATombstone() {
        assertNull(Tombstone.describe(ByteArray(0)))
        assertNull(Tombstone.describe("Input dispatching timed out".toByteArray()))
        assertNull(Tombstone.describe(byteArrayOf(0x52, 0x7f))) // length past the end
    }
}
