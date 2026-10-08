package com.vibethroughcode.ftree.kutumb.treesync

/** The person fields that sync one at a time. Wire names are stable; never reuse or rename one. */
enum class PersonField(val wire: String) {
    NAME("name"),
    GENDER("gender"),
    BIRTH_DATE("birthDate"),
    DEATH_DATE("deathDate"),
    DECEASED("deceased"),
    NOTES("notes"),
    ;

    companion object {
        fun fromWire(wire: String): PersonField? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The identity of a relationship: its kind and the two people it joins. There is no separate edge id,
 * so two phones that each add "A is a parent of B" are describing one edge, not creating two.
 *
 * Symmetric kinds are stored with the smaller id first, as the database does, so "A married B" and
 * "B married A" are the same key. Kinds this build does not know are kept as written and treated as
 * directed, so a newer release's edge survives a trip through an older one.
 */
data class EdgeKey(val type: String, val from: String, val to: String) {
    init {
        require(type !in SYMMETRIC || from <= to) { "symmetric edges are stored smaller id first; build them with EdgeKey.of" }
    }

    companion object {
        const val PARENT = "PARENT"
        const val SPOUSE = "SPOUSE"
        const val SIBLING = "SIBLING"
        private val SYMMETRIC = setOf(SPOUSE, SIBLING)

        fun of(type: String, a: String, b: String): EdgeKey =
            if (type in SYMMETRIC && a > b) EdgeKey(type, b, a) else EdgeKey(type, a, b)
    }
}

/**
 * One change to a shared tree. Applying any set of these, in any order and any number of times, gives
 * the same tree on every phone (see [TreeReplica]). Each carries a unique [id], so delivery can be
 * repeated safely, and the [hlc] reading that decides who wins when two phones disagree.
 */
sealed interface TreeOp {
    val id: String
    val hlc: Hlc

    /** Creates a person. The person is a bare record until fields arrive as [PersonSet]s. */
    data class PersonAdd(override val id: String, override val hlc: Hlc, val personId: String) : TreeOp

    /** Sets one field. A null value clears it. Latest reading wins, field by field. */
    data class PersonSet(
        override val id: String,
        override val hlc: Hlc,
        val personId: String,
        val field: PersonField,
        val value: String?,
    ) : TreeOp

    /** Removes a person from the tree. Edits older than this are dropped with them. */
    data class PersonDelete(override val id: String, override val hlc: Hlc, val personId: String) : TreeOp

    /** Brings a deleted person back, with everything that was ever set on them. */
    data class PersonRestore(override val id: String, override val hlc: Hlc, val personId: String) : TreeOp

    /** Adds a relationship, with an optional subtype (BIOLOGICAL, MARRIED, HALF, ...). */
    data class EdgeAdd(override val id: String, override val hlc: Hlc, val edge: EdgeKey, val subtype: String?) : TreeOp

    /** Removes a relationship. A later [EdgeAdd] of the same edge brings it back. */
    data class EdgeRemove(override val id: String, override val hlc: Hlc, val edge: EdgeKey) : TreeOp

    /** Changes only the subtype of a relationship. */
    data class EdgeSubtype(override val id: String, override val hlc: Hlc, val edge: EdgeKey, val subtype: String?) : TreeOp
}

/** Why an operation was refused outright. These are bugs or abuse, not family disagreements. */
enum class InvalidReason {
    BLANK_ID,
    BAD_CLOCK,
    SELF_RELATIONSHIP,
    BAD_VALUE,
    TOO_LONG,
}

object TreeOpRules {
    const val MAX_ID = 128
    const val MAX_NAME = 500
    const val MAX_NOTES = 20_000
    const val MAX_SUBTYPE = 64

    private val GENDERS = setOf("MALE", "FEMALE", "OTHER", "UNSPECIFIED")
    private val DATE = Regex("""^(?:(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?|--(\d{2})-(\d{2}))$""")

    /** Null when the operation is acceptable. */
    fun check(op: TreeOp): InvalidReason? {
        if (op.id.isBlank() || op.id.length > MAX_ID) return InvalidReason.BLANK_ID
        if (op.hlc.device.isEmpty() || op.hlc.device.length > Hlc.MAX_DEVICE_LENGTH || op.hlc.time < 0 || op.hlc.counter < 0) {
            return InvalidReason.BAD_CLOCK
        }
        return when (op) {
            is TreeOp.PersonAdd -> person(op.personId)
            is TreeOp.PersonDelete -> person(op.personId)
            is TreeOp.PersonRestore -> person(op.personId)
            is TreeOp.PersonSet -> person(op.personId) ?: value(op.field, op.value)
            is TreeOp.EdgeAdd -> edge(op.edge) ?: subtype(op.subtype)
            is TreeOp.EdgeRemove -> edge(op.edge)
            is TreeOp.EdgeSubtype -> edge(op.edge) ?: subtype(op.subtype)
        }
    }

    private fun person(id: String) = if (id.isBlank() || id.length > MAX_ID) InvalidReason.BLANK_ID else null

    private fun edge(e: EdgeKey): InvalidReason? = when {
        e.type.isBlank() || e.type.length > MAX_ID || e.from.isBlank() || e.to.isBlank() ||
            e.from.length > MAX_ID || e.to.length > MAX_ID -> InvalidReason.BLANK_ID
        e.from == e.to -> InvalidReason.SELF_RELATIONSHIP
        else -> null
    }

    private fun subtype(s: String?) = if (s != null && (s.isBlank() || s.length > MAX_SUBTYPE)) InvalidReason.BAD_VALUE else null

    private fun value(field: PersonField, value: String?): InvalidReason? = when (field) {
        PersonField.NAME -> if (value != null && value.length > MAX_NAME) InvalidReason.TOO_LONG else null
        PersonField.NOTES -> if (value != null && value.length > MAX_NOTES) InvalidReason.TOO_LONG else null
        PersonField.GENDER -> if (value in GENDERS) null else InvalidReason.BAD_VALUE
        PersonField.DECEASED -> if (value == "true" || value == "false") null else InvalidReason.BAD_VALUE
        PersonField.BIRTH_DATE, PersonField.DEATH_DATE -> if (value == null || isPartialDate(value)) null else InvalidReason.BAD_VALUE
    }

    /** `1938`, `1938-04`, `1938-04-17` or `--04-17`: the partial ISO-8601 forms the app stores. */
    fun isPartialDate(text: String): Boolean {
        val m = DATE.matchEntire(text) ?: return false
        val g = m.groupValues
        return if (g[4].isNotEmpty()) validMonthDay(g[4].toInt(), g[5].toInt())
        else (g[2].isEmpty() || g[2].toInt() in 1..12) &&
            (g[3].isEmpty() || validMonthDay(g[2].toInt(), g[3].toInt()))
    }

    private fun validMonthDay(month: Int, day: Int): Boolean {
        if (month !in 1..12) return false
        // 29 February is allowed because the year may be unknown or a leap year.
        val days = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return day in 1..days[month - 1]
    }
}
