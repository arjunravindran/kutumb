package com.vibethroughcode.ftree.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.R

const val SettingsMessageNotesToggleTag = "settings-message-notes-toggle"
const val SettingsMessageNotesNoteTag = "settings-message-notes-note"
const val SettingsMessageNotesOpenSystemTag = "settings-message-notes-open-system"

/**
 * Whether a new message earns a note on the phone. Read the way the birthday reminders are: off until
 * turned on, Android asked to allow notifications at the moment the switch is turned on and not
 * before, and the switch moves only once it has said yes. Refused for good, it offers the system
 * page where that can be undone.
 */
@Composable
fun MessageNotificationSettings() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val preferences = (context.applicationContext as FTreeApplication).container.messageNotificationPreferences
    val enabled by preferences.enabled.collectAsStateWithLifecycle()
    var refused by rememberSaveable { mutableStateOf(false) }
    var refusedForGood by rememberSaveable { mutableStateOf(false) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            refused = false
            refusedForGood = false
            preferences.setEnabled(true)
        } else {
            refused = true
            refusedForGood = activity
                ?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS) }
                ?: false
        }
    }

    Column {
        SettingsSwitch(
            title = stringResource(R.string.settings_messages_toggle),
            body = stringResource(if (enabled) R.string.settings_messages_on else R.string.settings_messages_off),
            checked = enabled,
            onCheckedChange = { wanted ->
                when {
                    !wanted -> preferences.setEnabled(false)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED -> ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> preferences.setEnabled(true)
                }
            },
            tag = SettingsMessageNotesToggleTag,
        )
        if (!enabled && refused) {
            Text(
                stringResource(R.string.settings_messages_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp).testTag(SettingsMessageNotesNoteTag),
            )
        }
        if (!enabled && refusedForGood) {
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
                modifier = Modifier.testTag(SettingsMessageNotesOpenSystemTag),
            ) { Text(stringResource(R.string.settings_reminders_open_settings)) }
        }
    }
}
