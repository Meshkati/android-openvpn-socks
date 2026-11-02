package io.github.meshkati.ovpnvless

/**
 * Reads where a native crash happened from its tombstone, the protobuf Android
 * keeps for it (ApplicationExitInfo, Android 12+): the signal, the abort
 * message and the crashing thread's backtrace. Everything else in it is
 * skipped on purpose, above all the log buffers, which hold this app's log
 * with the user's servers in it; also registers, memory and open files.
 *
 * Field numbers are from AOSP system/core/debuggerd/proto/tombstone.proto.
 */
object Tombstone {
    const val MAX_FRAMES = 64

    // Tombstone
    private const val TID = 6
    private const val SIGNAL = 10
    private const val ABORT_MESSAGE = 14
    private const val THREADS = 16 // map<uint32, Thread>: entries with key 1, value 2

    // Signal
    private const val SIG_NUMBER = 1
    private const val SIG_NAME = 2
    private const val SIG_CODE = 3
    private const val SIG_CODE_NAME = 4
    private const val SIG_HAS_FAULT_ADDRESS = 8
    private const val SIG_FAULT_ADDRESS = 9

    // Thread
    private const val THREAD_ID = 1
    private const val THREAD_NAME = 2
    private const val THREAD_BACKTRACE = 4

    // BacktraceFrame
    private const val FRAME_REL_PC = 1
    private const val FRAME_FUNCTION = 4
    private const val FRAME_OFFSET = 5
    private const val FRAME_FILE = 6
    private const val FRAME_BUILD_ID = 8

    /** Null if [bytes] is not a tombstone this can read. */
    fun describe(bytes: ByteArray): String? = try {
        describeOrThrow(bytes)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun describeOrThrow(bytes: ByteArray): String? {
        val top = Message.parse(bytes)
        val signal = top.message(SIGNAL)
        val abort = top.string(ABORT_MESSAGE)
        val threads = top.all(THREADS).mapNotNull { entry ->
            val e = Message.parse(entry.bytes ?: return@mapNotNull null)
            e.message(2)?.let { e.long(1).toInt() to it }
        }
        val tid = top.long(TID).toInt()
        val thread = (threads.firstOrNull { it.first == tid } ?: threads.firstOrNull())?.second
        if (signal == null && abort.isEmpty() && thread == null) return null

        return buildString {
            if (signal != null) {
                append("signal ${signal.long(SIG_NUMBER).toInt()} (${signal.string(SIG_NAME)}), ")
                append("code ${signal.long(SIG_CODE).toInt()} (${signal.string(SIG_CODE_NAME)})")
                if (signal.long(SIG_HAS_FAULT_ADDRESS) != 0L) {
                    append(", fault addr 0x").append(java.lang.Long.toHexString(signal.long(SIG_FAULT_ADDRESS)))
                }
                append('\n')
            }
            if (abort.isNotEmpty()) append("abort: ").append(abort).append('\n')
            if (thread != null) {
                append("thread ${thread.long(THREAD_ID).toInt()} \"${thread.string(THREAD_NAME)}\"\n")
                val frames = thread.all(THREAD_BACKTRACE)
                for ((i, f) in frames.take(MAX_FRAMES).withIndex()) {
                    val frame = Message.parse(f.bytes ?: continue)
                    append("  #%02d pc %016x  %s".format(i, frame.long(FRAME_REL_PC), fileName(frame.string(FRAME_FILE))))
                    val function = frame.string(FRAME_FUNCTION)
                    if (function.isNotEmpty()) append(" ($function+${frame.long(FRAME_OFFSET)})")
                    val buildId = frame.string(FRAME_BUILD_ID)
                    if (buildId.isNotEmpty()) append(" (BuildId: $buildId)")
                    append('\n')
                }
                if (frames.size > MAX_FRAMES) append("  … ${frames.size - MAX_FRAMES} more frames\n")
            }
        }.trimEnd()
    }

    /**
     * The app's own libraries live under a random install path
     * (/data/app/~~…==/io.github…-…==/lib/arm64/libovpn3.so): keep the file name.
     */
    private fun fileName(path: String) =
        if (path.startsWith("/data/app/")) path.substringAfterLast('/') else path

    /** The fields of one protobuf message: enough of the wire format for a tombstone. */
    private class Message(private val fields: List<Field>) {
        class Field(val number: Int, val value: Long, val bytes: ByteArray?)

        fun all(number: Int) = fields.filter { it.number == number }
        fun long(number: Int) = fields.lastOrNull { it.number == number }?.value ?: 0L
        fun string(number: Int) = fields.lastOrNull { it.number == number }?.bytes?.toString(Charsets.UTF_8).orEmpty()
        fun message(number: Int) = fields.lastOrNull { it.number == number }?.bytes?.let(::parse)

        companion object {
            fun parse(b: ByteArray): Message {
                val out = ArrayList<Field>()
                var i = 0
                fun varint(): Long {
                    var result = 0L
                    var shift = 0
                    while (true) {
                        require(i < b.size && shift < 64) { "bad varint" }
                        val x = b[i++].toInt() and 0xff
                        result = result or ((x and 0x7f).toLong() shl shift)
                        if (x < 0x80) return result
                        shift += 7
                    }
                }
                fun fixed(size: Int): Long {
                    require(size <= b.size - i) { "truncated" }
                    var v = 0L
                    for (k in 0 until size) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
                    i += size
                    return v
                }
                while (i < b.size) {
                    val key = varint()
                    val number = (key ushr 3).toInt()
                    when (val wire = (key and 7).toInt()) {
                        0 -> out += Field(number, varint(), null)
                        1 -> out += Field(number, fixed(8), null)
                        2 -> {
                            val len = varint()
                            require(len >= 0 && len <= b.size - i) { "truncated" }
                            out += Field(number, 0, b.copyOfRange(i, i + len.toInt()))
                            i += len.toInt()
                        }
                        5 -> out += Field(number, fixed(4), null)
                        else -> throw IllegalArgumentException("wire type $wire")
                    }
                }
                return Message(out)
            }
        }
    }
}
