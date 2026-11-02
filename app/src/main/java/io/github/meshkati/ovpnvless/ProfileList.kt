package io.github.meshkati.ovpnvless

/**
 * The saved profiles in their order, and the one in use. Pure: [Profiles]
 * keeps it in [Settings] as "1,3,2" and an id.
 */
data class ProfileList(val ids: List<Long>, val active: Long) {

    /** With [id] added at the end, or right after [after] (a duplicate goes next to its original). */
    fun add(id: Long, after: Long? = null): ProfileList {
        if (id in ids) return this
        val at = after?.let { ids.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: ids.size
        return copy(ids = ids.toMutableList().apply { add(at, id) })
    }

    /** Without [id]. If it was in use, the first one left is used instead. */
    fun remove(id: Long): ProfileList {
        val left = ids - id
        return ProfileList(left, if (active in left) active else left.firstOrNull() ?: NONE)
    }

    /** [id] back at [index], and in use again if [wasActive]: undoes [remove]. */
    fun restore(id: Long, index: Int, wasActive: Boolean): ProfileList {
        if (id in ids) return this
        val list = ids.toMutableList().apply { add(index.coerceIn(0, size), id) }
        return ProfileList(list, if (wasActive || active == NONE) id else active)
    }

    /** [id] moved [by] places, negative is up. Stops at either end. */
    fun move(id: Long, by: Int): ProfileList {
        val from = ids.indexOf(id)
        if (from < 0) return this
        val to = (from + by).coerceIn(0, ids.lastIndex)
        if (to == from) return this
        return copy(ids = ids.toMutableList().apply { removeAt(from); add(to, id) })
    }

    /** With [id] in use, if it is in the list. */
    fun use(id: Long): ProfileList = if (id in ids) copy(active = id) else this

    fun format(): String = ids.joinToString(",")

    companion object {
        /** No profile: ids start at 1. */
        const val NONE = 0L

        /** Reads [format]'s text. Unknown or missing [active] falls back to the first profile. */
        fun parse(order: String, active: Long): ProfileList {
            val ids = order.split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > NONE }.distinct()
            return ProfileList(ids, if (active in ids) active else ids.firstOrNull() ?: NONE)
        }
    }
}

/** Names of profiles, as shown. Pure. */
object ProfileNames {

    /** What the user typed, else the OpenVPN server's name, else null (the caller's "My profile"). */
    fun display(name: String, host: String?): String? =
        name.trim().ifEmpty { null } ?: host?.trim()?.ifEmpty { null }

    /**
     * The name for a duplicate of [name]: "Travel NL" → "Travel NL 2", then
     * "Travel NL 3" if that is [taken]. A duplicate of "Work 2" is "Work 3".
     */
    fun copy(name: String, taken: Collection<String>): String {
        val trimmed = name.trim()
        val match = Regex("""^(.*\S)\s+(\d+)$""").matchEntire(trimmed)
        val base = match?.groupValues?.get(1) ?: trimmed
        val takenLower = taken.map { it.trim().lowercase() }.toSet()
        var n = (match?.groupValues?.get(2)?.toIntOrNull() ?: 1) + 1
        while ("$base $n".lowercase() in takenLower) n++
        return "$base $n"
    }

    /** The letter on the profile's badge: its first letter or digit, upper case. */
    fun initial(name: String): String {
        val c = name.firstOrNull { it.isLetterOrDigit() } ?: return "#"
        return c.uppercase()
    }
}
