package com.vibethroughcode.ftree.kutumb.treesync

/**
 * A hybrid logical clock reading: wall-clock milliseconds, a counter for events inside one
 * millisecond (or behind a clock that ran backwards), and the device that stamped it.
 *
 * Ordering is the whole point: `(time, counter, device)`, compared in that order, is a total order
 * over every reading any phone will ever make, so two phones that hold the same edits agree on which
 * one is later without talking. The device id only breaks exact ties; it carries no meaning.
 */
data class Hlc(val time: Long, val counter: Int, val device: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int {
        val t = time.compareTo(other.time)
        if (t != 0) return t
        val c = counter.compareTo(other.counter)
        return if (c != 0) c else device.compareTo(other.device)
    }

    /** `"<time>.<counter>.<device>"`; the device may itself contain dots. */
    fun format(): String = "$time.$counter.$device"

    companion object {
        /** Null for anything that is not a well-formed reading; a bad stamp must not be guessed at. */
        fun parse(text: String): Hlc? {
            val parts = text.split('.', limit = 3)
            if (parts.size != 3) return null
            val time = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val counter = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val device = parts[2].takeIf { it.isNotEmpty() && it.length <= MAX_DEVICE_LENGTH } ?: return null
            return Hlc(time, counter, device)
        }

        const val MAX_DEVICE_LENGTH = 64
    }
}

/**
 * One phone's clock for one shared tree.
 *
 * [tick] stamps a local edit and never returns a reading at or below any earlier one, even if the
 * phone's own clock steps backwards. [observe] folds in a reading seen on an incoming edit so the next
 * local edit sorts after it, which is what makes "I saw your change, then made mine" come out right.
 *
 * A reading further ahead of this phone's wall clock than [maxFutureMs] is refused. Otherwise one
 * phone set to the year 2090 would win every field in the tree for good.
 */
class HybridClock(
    private val device: String,
    private val wallClock: () -> Long,
    private val maxFutureMs: Long = DEFAULT_MAX_FUTURE_MS,
    start: Hlc? = null,
) {
    private var lastTime: Long = start?.time ?: 0L
    private var lastCounter: Int = start?.counter ?: 0

    init {
        require(device.isNotEmpty() && device.length <= Hlc.MAX_DEVICE_LENGTH) { "device id must be 1..${Hlc.MAX_DEVICE_LENGTH} characters" }
    }

    /** The latest reading made or observed; persist it so a restart cannot reuse a stamp. */
    @Synchronized fun last(): Hlc = Hlc(lastTime, lastCounter, device)

    @Synchronized fun tick(): Hlc {
        val now = wallClock()
        if (now > lastTime) {
            lastTime = now
            lastCounter = 0
        } else {
            lastCounter += 1
        }
        return Hlc(lastTime, lastCounter, device)
    }

    /** False, and nothing changes, when [remote] is further ahead than this phone will accept. */
    @Synchronized fun observe(remote: Hlc): Boolean {
        val now = wallClock()
        if (remote.time > now + maxFutureMs) return false
        val newest = maxOf(lastTime, remote.time, now)
        lastCounter = when {
            newest == lastTime && newest == remote.time -> maxOf(lastCounter, remote.counter) + 1
            newest == lastTime -> lastCounter + 1
            newest == remote.time -> remote.counter + 1
            else -> 0
        }
        lastTime = newest
        return true
    }

    companion object {
        /** A day: wide enough for a badly set phone to recover, narrow enough to stop a runaway one. */
        const val DEFAULT_MAX_FUTURE_MS: Long = 24L * 60 * 60 * 1000
    }
}
