package com.vibethroughcode.ftree

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vibethroughcode.ftree.kutumb.messaging.MessageNotifier
import com.vibethroughcode.ftree.reminders.ReminderNotifier
import com.vibethroughcode.ftree.transfer.openedTree
import com.vibethroughcode.ftree.ui.ReminderTap
import com.vibethroughcode.ftree.ui.ThreadTap
import com.vibethroughcode.ftree.ui.FTreeApp
import com.vibethroughcode.ftree.ui.theme.FTreeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    /**
     * A file handed to the app from outside, waiting to be looked at.
     *
     * Held here rather than read from the intent where it is needed, because an intent is delivered
     * once but a composition happens many times: reading it directly would re-open the same file on
     * every rotation, throwing away whatever the reader had decided about the import so far.
     */
    private val opened = MutableStateFlow<Uri?>(null)

    /** Read-only, and made once: a flow built inside the composition would be a new one each time. */
    private val openedFile: StateFlow<Uri?> = opened.asStateFlow()

    /** A birthday note that was tapped, waiting to be followed — held for the same reason as [opened]. */
    private val tapped = MutableStateFlow<ReminderTap?>(null)
    private val tappedReminder: StateFlow<ReminderTap?> = tapped.asStateFlow()

    /** A message note that was tapped, waiting to be followed. */
    private val threadTapped = MutableStateFlow<ThreadTap?>(null)
    private val tappedThread: StateFlow<ThreadTap?> = threadTapped.asStateFlow()

    private fun threadTap(intent: Intent?): ThreadTap? {
        if (intent?.action != MessageNotifier.ACTION_OPEN_THREAD) return null
        val person = intent.getStringExtra(MessageNotifier.EXTRA_PERSON) ?: return null
        return ThreadTap(intent.getStringExtra(MessageNotifier.EXTRA_TREE), person)
    }

    /**
     * The note is for a conversation in one particular tree. On a fresh start the right tree is opened
     * before anything is drawn; in a running app that has another tree open, the main screen is
     * started again on the right one with the same tap.
     */
    private fun inTheTapsTree(tap: ThreadTap?, fresh: Boolean): Boolean {
        val tree = tap?.treeId ?: return true
        val app = application as FTreeApplication
        if (tree == app.container.tree.id) return true
        if (!app.switchTo(tree)) return true // a tree that no longer exists: just open the app
        if (!fresh) {
            startActivity(
                Intent(this, MainActivity::class.java).setAction(MessageNotifier.ACTION_OPEN_THREAD)
                    .putExtra(MessageNotifier.EXTRA_PERSON, tap.personId).putExtra(MessageNotifier.EXTRA_TREE, tree)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            return false
        }
        return true
    }

    private fun reminderTap(intent: Intent?): ReminderTap? =
        if (intent?.action == ReminderNotifier.ACTION_OPEN) ReminderTap(intent.getStringExtra(ReminderNotifier.EXTRA_PERSON)) else null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start. A recreated activity is handed the same intent again, and that is
        // the app being rebuilt, not somebody opening a file a second time.
        if (savedInstanceState == null) {
            opened.value = openedTree(intent)
            tapped.value = reminderTap(intent)
            threadTap(intent).also { tap -> if (inTheTapsTree(tap, fresh = true)) threadTapped.value = tap }
        }
        setContent {
            FTreeTheme {
                FTreeApp(
                    opened = openedFile,
                    onOpened = { opened.value = null },
                    reminder = tappedReminder,
                    onReminderFollowed = { tapped.value = null },
                    thread = tappedThread,
                    onThreadFollowed = { threadTapped.value = null },
                )
            }
        }
    }

    /** The app was already running when the file was opened. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opened.value = openedTree(intent)
        tapped.value = reminderTap(intent)
        threadTap(intent).also { tap -> if (inTheTapsTree(tap, fresh = false)) threadTapped.value = tap }
    }
}
