package com.vibethroughcode.ftree.ui.welcome

import android.content.Context

/** What to do about the first-run welcome. */
enum class WelcomeAction { SHOW, MARK_SEEN, NOTHING }

/**
 * Whether somebody should be welcomed, kept apart from the screen so it can be tested.
 *
 * A welcome is for a new install with an empty tree. Somebody who updated an older version has people
 * in their tree already and is not a newcomer, so they are marked as welcomed without being shown it.
 * [stored] is what was saved: `true` once welcomed, `false` only when something asks for it to be shown
 * again (a test), and `null` when nothing was ever decided.
 */
object WelcomePolicy {
    fun decide(stored: Boolean?, runningUnderTest: Boolean, people: Int): WelcomeAction = when {
        stored == true -> WelcomeAction.NOTHING
        // The instrumented tests start from empty installs all the time and must not meet a welcome
        // they did not ask for; one that does want it says so by storing `false`.
        stored == null && runningUnderTest -> WelcomeAction.NOTHING
        stored == null && people > 0 -> WelcomeAction.MARK_SEEN
        else -> WelcomeAction.SHOW
    }
}

/** Whether this phone has welcomed its reader yet. One flag, kept in preferences. */
class WelcomePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("welcome", Context.MODE_PRIVATE)

    /** `null` until something has decided; see [WelcomePolicy]. */
    fun stored(): Boolean? = if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null

    fun markSeen() = prefs.edit().putBoolean(KEY, true).apply()

    /** Asks for the welcome to be shown even where it would be held back. For tests. */
    fun showAgain() = prefs.edit().putBoolean(KEY, false).commit()

    /** Forgets everything, so the next decision is a first one. For tests. */
    fun forget() = prefs.edit().remove(KEY).commit()

    companion object {
        private const val KEY = "seen"

        /** True inside an instrumented test, where the test runner's classes are loaded alongside the app. */
        fun runningUnderTest(): Boolean =
            runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry") }.isSuccess
    }
}
