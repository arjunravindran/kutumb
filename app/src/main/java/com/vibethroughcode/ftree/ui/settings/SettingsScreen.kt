package com.vibethroughcode.ftree.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibethroughcode.ftree.BuildConfig
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.kutumb.sync.SyncPreferences
import com.vibethroughcode.ftree.book.TemplateDownloader
import com.vibethroughcode.ftree.data.KinshipLanguage
import com.vibethroughcode.ftree.ui.common.SectionRule
import com.vibethroughcode.ftree.kutumb.sync.SyncStatus
import com.vibethroughcode.ftree.ui.nearby.NearbyMode
import com.vibethroughcode.ftree.ui.common.READABLE_MEASURE
import com.vibethroughcode.ftree.ui.common.ReadingColumns
import com.vibethroughcode.ftree.ui.theme.FTreeText
import com.vibethroughcode.ftree.ui.theme.FTreeTheme
import com.vibethroughcode.ftree.update.AvailableUpdate
import com.vibethroughcode.ftree.update.UpdateFailure
import com.vibethroughcode.ftree.update.UpdateState

const val SettingsUpdatesToggleTag = "settings-updates-toggle"
const val SettingsPhotosToggleTag = "settings-photos-toggle"
const val SettingsWordsEnglishTag = "settings-words-english"
const val SettingsWordsHindiTag = "settings-words-hindi"
const val SettingsExportTag = "settings-export"
const val SettingsImportTag = "settings-import"
const val SettingsBetaToggleTag = "settings-beta-toggle"
const val SettingsTemplatesToggleTag = "settings-templates-toggle"
const val SettingsBackTag = "settings-back"

/**
 * The one sentence a catalogue check earns (#214).
 *
 * "Nothing new" covers both a catalogue no newer than the one already verified and an older one
 * replayed at the app, deliberately: they are the same answer to the reader, and the second is
 * refused either way.
 */
private fun templateRefreshMessage(refresh: TemplateDownloader.Refresh): Int = when (refresh) {
    TemplateDownloader.Refresh.Off -> R.string.settings_templates_explainer
    TemplateDownloader.Refresh.None -> R.string.settings_templates_none
    TemplateDownloader.Refresh.Unchanged -> R.string.settings_templates_unchanged
    is TemplateDownloader.Refresh.Updated -> R.string.settings_templates_updated
    is TemplateDownloader.Refresh.Refused -> R.string.settings_templates_refused
    is TemplateDownloader.Refresh.Failed -> R.string.settings_templates_failed
}
const val SettingsBetaConfirmTag = "settings-beta-confirm"
const val SettingsNearbyToggleTag = "settings-nearby-toggle"
const val SettingsSyncToggleTag = "settings-sync-toggle"
const val SettingsSyncRelaysTag = "settings-sync-relays"
const val SettingsSyncRelayPickerTag = "settings-sync-relay-picker"
const val SettingsSyncAdvancedTag = "settings-sync-advanced"
const val SettingsNearbySendTag = "settings-nearby-send"
const val SettingsNearbyReceiveTag = "settings-nearby-receive"
const val SettingsNearbyRenameTag = "settings-nearby-rename"
const val SettingsFinePrintTag = "settings-fineprint"
const val SettingsContactsTag = "settings-contacts"

/**
 * The places Settings is divided into. The first screen is a plain list of these, as big rows like the
 * ones under More, because one page of about ten sections is too much for the readers this is made for.
 * Each page is the same controls as before, moved, with the same tags.
 */
enum class SettingsPage(val title: Int, val body: Int, val tag: String) {
    NAMES(R.string.settings_hub_names, R.string.settings_hub_names_body, "settings-page-names"),
    REMINDERS(R.string.settings_hub_reminders, R.string.settings_hub_reminders_body, "settings-page-reminders"),
    UPDATES(R.string.settings_hub_updates, R.string.settings_hub_updates_body, "settings-page-updates"),
    DATA(R.string.settings_hub_data, R.string.settings_hub_data_body, "settings-page-data"),
    NEARBY(R.string.settings_hub_nearby, R.string.settings_hub_nearby_body, "settings-page-nearby"),
    TREES(R.string.settings_hub_trees, R.string.settings_hub_trees_body, "settings-page-trees"),
    SYNC(R.string.settings_hub_sync, R.string.settings_hub_sync_body, "settings-page-sync"),
    ABOUT(R.string.settings_hub_about, R.string.settings_hub_about_body, "settings-page-about"),
}

/**
 * Settings, and the only place in the app that can reach the network.
 *
 * It opens on a list of [SettingsPage]s and shows one page at a time; Back, or the arrow, returns to the
 * list. That is state inside this screen, not a navigation destination, so nothing outside it changes.
 *
 * The updater is written to be read as much as used: the switch says plainly that it is the one
 * networked thing here, and that an update installs *over* this copy so the family is carried
 * across. That last point is the entire reason the feature exists — without it, moving to a new
 * version means exporting, uninstalling, reinstalling and importing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onExport: () -> Unit,
    onImport: () -> Unit,
    onNearby: (NearbyMode) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
    /** Opens the import of contacts. */
    onContacts: () -> Unit = {},
    focusReminders: Boolean = false,
    onRemindersFocused: () -> Unit = {},
) {
    val context = LocalContext.current
    val enabled by viewModel.updatesEnabled.collectAsStateWithLifecycle()
    val state by viewModel.updateState.collectAsStateWithLifecycle()
    val photosInChart by viewModel.photosInChart.collectAsStateWithLifecycle()
    val kinshipLanguage by viewModel.kinshipLanguage.collectAsStateWithLifecycle()
    val betaChannel by viewModel.betaChannel.collectAsStateWithLifecycle()
    val templatesEnabled by viewModel.templatesEnabled.collectAsStateWithLifecycle()
    val templateRefresh by viewModel.templateRefresh.collectAsStateWithLifecycle()
    var confirmingBeta by remember { mutableStateOf(false) }
    val nearbyEnabled by viewModel.nearbyEnabled.collectAsStateWithLifecycle()
    val syncEnabled by viewModel.syncEnabled.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val syncRelays by viewModel.syncRelays.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val trustedOnly by viewModel.trustedOnly.collectAsStateWithLifecycle()
    val trustedDevices by viewModel.trustedDevices.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }

    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    // The birthday bell on People sends the reader straight to the reminders.
    LaunchedEffect(focusReminders) { if (focusReminders) page = SettingsPage.REMINDERS }
    BackHandler(enabled = page != null) { page = null }
    val shown = page

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(shown?.title ?: R.string.settings_title)) },
                navigationIcon = {
                    if (shown != null) {
                        IconButton(onClick = { page = null }, modifier = Modifier.testTag(SettingsBackTag)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        when (shown) {
            null -> SettingsHub(onOpen = { page = it })

            SettingsPage.NAMES -> {
                /*
                 * Two short sections that stand beside each other on a window wide enough for two, rather
                 * than leave half of it empty. The scroll stays outside the columns.
                 */
                ReadingColumns {
                    Column {
                        SectionRule(stringResource(R.string.settings_section_words))

                        Text(
                            stringResource(R.string.settings_words_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = kinshipLanguage == KinshipLanguage.ENGLISH,
                                onClick = { viewModel.setKinshipLanguage(KinshipLanguage.ENGLISH) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                modifier = Modifier.testTag(SettingsWordsEnglishTag),
                            ) { Text(stringResource(R.string.settings_words_english)) }

                            SegmentedButton(
                                selected = kinshipLanguage == KinshipLanguage.HINDI,
                                onClick = { viewModel.setKinshipLanguage(KinshipLanguage.HINDI) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                modifier = Modifier.testTag(SettingsWordsHindiTag),
                            ) { Text(stringResource(R.string.settings_words_hindi)) }
                        }
                    }

                    Column {
                        SectionRule(stringResource(R.string.settings_section_chart))

                        SettingsSwitch(
                            title = stringResource(R.string.settings_photos_toggle),
                            body = stringResource(R.string.settings_photos_explainer),
                            checked = photosInChart,
                            onCheckedChange = viewModel::setPhotosInChart,
                            tag = SettingsPhotosToggleTag,
                        )
                    }
                }
            }

            SettingsPage.REMINDERS -> Column(Modifier.widthIn(max = READABLE_MEASURE)) {
                ReminderSettings(viewModel, focus = focusReminders, onFocused = onRemindersFocused)
            }

            // Absent from the Play build: Google Play does not allow an app to update itself.
            SettingsPage.UPDATES -> Column(Modifier.widthIn(max = READABLE_MEASURE)) {
                SectionRule(stringResource(R.string.settings_section_updates))

                SettingsSwitch(
                    title = stringResource(R.string.settings_updates_toggle),
                    body = stringResource(R.string.settings_updates_explainer),
                    checked = enabled,
                    onCheckedChange = viewModel::setUpdatesEnabled,
                    tag = SettingsUpdatesToggleTag,
                )

                /*
                 * Under the updates switch and gated on it, because it makes the same request to the
                 * same place (#214). Its own switch rather than a mode of that one: this is consent
                 * to ask about book templates, and consent to fetch one when you tap it - never
                 * consent to fetch templates on its own.
                 */
                if (viewModel.templatesOffered) {
                    AnimatedVisibility(visible = enabled) {
                        SettingsSwitch(
                            title = stringResource(R.string.settings_templates_toggle),
                            body = templateRefresh?.let { stringResource(templateRefreshMessage(it)) }
                                ?: stringResource(R.string.settings_templates_explainer),
                            checked = templatesEnabled,
                            onCheckedChange = viewModel::setTemplatesEnabled,
                            tag = SettingsTemplatesToggleTag,
                        )
                    }
                }

                AnimatedVisibility(visible = enabled) {
                    UpdatePanel(
                        state = state,
                        onCheck = viewModel::check,
                        onDownload = viewModel::download,
                        onCancel = viewModel::cancel,
                        onInstall = { file ->
                            if (viewModel.canInstall()) {
                                viewModel.install(file)
                            } else {
                                context.startActivity(viewModel.permissionIntent())
                            }
                        },
                        onSkip = viewModel::skip,
                        onDismiss = viewModel::dismissFailure,
                    )
                }

                /*
                 * Last on the page, and meant to be.
                 *
                 * A beta is an unfinished build of an app somebody keeps their family in. Putting it
                 * under the ordinary updates is the honest placement: it is for people who came looking
                 * for it, and nobody should meet it while turning ordinary updates on.
                 */
                SectionRule(stringResource(R.string.settings_section_beta))
                SettingsSwitch(
                    title = stringResource(R.string.settings_beta_toggle),
                    body = stringResource(
                        if (enabled) R.string.settings_beta_explainer
                        else R.string.settings_beta_explainer_updates_off
                    ),
                    checked = betaChannel,
                    enabled = enabled,
                    onCheckedChange = { wanted ->
                        // Turning it off is not a decision worth interrupting; turning it on is.
                        if (wanted) confirmingBeta = true else viewModel.setBetaChannel(false)
                    },
                    tag = SettingsBetaToggleTag,
                )
            }

            SettingsPage.DATA -> Column(Modifier.widthIn(max = READABLE_MEASURE)) {
                SectionRule(stringResource(R.string.settings_section_data))

                SettingsAction(
                    icon = { Icon(Icons.Default.Upload, contentDescription = null) },
                    title = stringResource(R.string.settings_export),
                    body = stringResource(R.string.settings_export_body),
                    onClick = onExport,
                    modifier = Modifier.testTag(SettingsExportTag),
                )
                SettingsAction(
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    title = stringResource(R.string.settings_import),
                    body = stringResource(R.string.settings_import_body),
                    onClick = onImport,
                    modifier = Modifier.testTag(SettingsImportTag),
                )
                SettingsAction(
                    icon = { Icon(Icons.Default.Contacts, contentDescription = null) },
                    title = stringResource(R.string.contacts_entry),
                    body = stringResource(R.string.contacts_entry_body),
                    onClick = onContacts,
                    modifier = Modifier.testTag(SettingsContactsTag),
                )
            }

            /*
             * Next to the data: export, import and this are three answers to one question: how does a
             * tree get from here to there. Off until switched on, and even then nothing is announced
             * until one of the two screens is open — there is no "always visible" and no "accept
             * without asking", and both absences are the design.
             */
            SettingsPage.NEARBY -> Column(Modifier.widthIn(max = READABLE_MEASURE)) {
                SectionRule(stringResource(R.string.settings_section_nearby))

                SettingsSwitch(
                    title = stringResource(R.string.settings_nearby_toggle),
                    body = stringResource(R.string.settings_nearby_explainer),
                    checked = nearbyEnabled,
                    onCheckedChange = viewModel::setNearbyEnabled,
                    tag = SettingsNearbyToggleTag,
                )

                AnimatedVisibility(visible = nearbyEnabled) {
                    Column {
                        SettingsAction(
                            icon = { Icon(Icons.Default.Send, contentDescription = null) },
                            title = stringResource(R.string.settings_nearby_send),
                            body = stringResource(R.string.settings_nearby_send_body),
                            onClick = { onNearby(NearbyMode.SEND) },
                            modifier = Modifier.testTag(SettingsNearbySendTag),
                        )
                        SettingsAction(
                            icon = { Icon(Icons.Default.CallReceived, contentDescription = null) },
                            title = stringResource(R.string.settings_nearby_receive),
                            body = stringResource(R.string.settings_nearby_receive_body),
                            onClick = { onNearby(NearbyMode.RECEIVE) },
                            modifier = Modifier.testTag(SettingsNearbyReceiveTag),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.settings_nearby_name_title),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(deviceName, style = MaterialTheme.typography.bodyLarge)
                            }
                            TextButton(
                                onClick = { renaming = true },
                                modifier = Modifier.testTag(SettingsNearbyRenameTag),
                            ) { Text(stringResource(R.string.settings_nearby_name_edit)) }
                        }
                        Text(
                            stringResource(R.string.settings_nearby_name_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                        )

                        SettingsSwitch(
                            title = stringResource(R.string.settings_nearby_trusted_only),
                            body = stringResource(R.string.settings_nearby_trusted_only_body),
                            checked = trustedOnly,
                            onCheckedChange = viewModel::setTrustedOnly,
                            tag = "settings-nearby-trusted-only",
                        )

                        Text(
                            stringResource(R.string.settings_nearby_trusted_title).uppercase(),
                            style = FTreeText.sectionLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        if (trustedDevices.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_nearby_trusted_none),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            trustedDevices.entries.sortedBy { it.value.lowercase() }.forEach { (id, name) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    val forgetLabel = stringResource(R.string.settings_nearby_forget_named, name)
                                    TextButton(
                                        onClick = { viewModel.forgetDevice(id) },
                                        modifier = Modifier.semantics { contentDescription = forgetLabel },
                                    ) { Text(stringResource(R.string.settings_nearby_forget)) }
                                }
                            }
                            TextButton(onClick = viewModel::forgetAllDevices) {
                                Text(stringResource(R.string.settings_nearby_forget_all))
                            }
                        }
                    }
                }
            }

            SettingsPage.TREES -> Column(Modifier.widthIn(max = READABLE_MEASURE)) {
                TreesSettings()
            }

            /*
             * Off until switched on. Once on it uses the default relay unless the person chose others,
             * which they can do under Advanced. Messages are end-to-end encrypted before they leave the phone.
             * The note about new messages is here because messages travel by this.
             */
            SettingsPage.SYNC -> Column(Modifier.widthIn(max = READABLE_MEASURE)) {
                SectionRule(stringResource(R.string.settings_section_sync))

                SettingsSwitch(
                    title = stringResource(R.string.settings_sync_toggle),
                    body = stringResource(R.string.settings_sync_explainer),
                    checked = syncEnabled,
                    onCheckedChange = viewModel::setSyncEnabled,
                    tag = SettingsSyncToggleTag,
                )

                AnimatedVisibility(visible = syncEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        var advanced by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = { advanced = !advanced },
                            modifier = Modifier.testTag(SettingsSyncAdvancedTag),
                        ) {
                            Text(stringResource(if (advanced) R.string.settings_sync_advanced_hide else R.string.settings_sync_advanced_show))
                        }
                        var relayText by remember(syncRelays) { mutableStateOf(syncRelays.joinToString(", ")) }
                        var refused by remember { mutableStateOf(emptyList<String>()) }
                        if (advanced) {
                        OutlinedTextField(
                            value = relayText,
                            onValueChange = { relayText = it.take(1000) },
                            label = { Text(stringResource(R.string.settings_sync_relays_label)) },
                            minLines = 2,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(SettingsSyncRelaysTag),
                        )
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.testTag(SettingsSyncRelayPickerTag),
                            ) {
                                Text(stringResource(R.string.settings_sync_relays_pick))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                SyncPreferences.SUGGESTED_RELAYS.forEach { relay ->
                                    DropdownMenuItem(
                                        text = { Text(relay.removePrefix("wss://")) },
                                        onClick = {
                                            menuOpen = false
                                            val have = relayText.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                                            if (relay !in have && have.size < SyncPreferences.MAX_RELAYS) {
                                                relayText = (have + relay).joinToString(", ")
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        if (refused.isNotEmpty()) {
                            Text(
                                stringResource(R.string.settings_sync_relays_refused, refused.joinToString(", ")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(onClick = { refused = viewModel.setSyncRelays(relayText) }) {
                            Text(stringResource(R.string.settings_sync_relays_save))
                        }
                        }
                        Text(
                            stringResource(
                                when (syncStatus) {
                                    SyncStatus.ON -> R.string.settings_sync_status_on
                                    SyncStatus.NEEDS_SETUP -> R.string.settings_sync_status_setup
                                    SyncStatus.OFF -> R.string.settings_sync_status_off
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                MessageNotificationSettings()
            }

            SettingsPage.ABOUT -> Column(Modifier.widthIn(max = READABLE_MEASURE)) {
                SectionRule(stringResource(R.string.settings_section_about))

                Text(
                    stringResource(R.string.about_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = FTreeText.record,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )

                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.RELEASES_PAGE_URL))
                        )
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.settings_source))
                    Spacer(Modifier.size(6.dp))
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }

                /*
                 * The permissions paragraph and the licence notice are long and are read once, if
                 * ever, so they sit behind a row of their own rather than lengthening the page.
                 */
                var showFinePrint by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { showFinePrint = !showFinePrint },
                    modifier = Modifier.padding(top = 4.dp).testTag(SettingsFinePrintTag),
                ) {
                    Text(
                        stringResource(
                            if (showFinePrint) R.string.settings_fineprint_hide else R.string.settings_fineprint_show,
                        ),
                    )
                }
                AnimatedVisibility(visible = showFinePrint) {
                    Column {
                        Text(
                            stringResource(R.string.settings_permissions_title).uppercase(),
                            style = FTreeText.sectionLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            stringResource(R.string.settings_permissions_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            stringResource(R.string.about_fonts),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
    }

    if (renaming) {
        RenameDeviceDialog(
            current = deviceName,
            generated = viewModel.generatedDeviceName,
            onDismiss = { renaming = false },
            onDone = { name ->
                renaming = false
                viewModel.renameDevice(name)
            },
        )
    }

    if (confirmingBeta) {
        BetaOptInDialog(
            onDismiss = { confirmingBeta = false },
            onConfirm = {
                confirmingBeta = false
                viewModel.setBetaChannel(true)
            },
        )
    }
}

/** The first thing Settings shows: where to go, as big plain rows. */
@Composable
private fun SettingsHub(onOpen: (SettingsPage) -> Unit) {
    Column(Modifier.widthIn(max = READABLE_MEASURE)) {
        SettingsPage.entries
            // The Play build cannot update itself, so it has no updates page to offer.
            .filter { it != SettingsPage.UPDATES || BuildConfig.SELF_UPDATE }
            .forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .clickable { onOpen(entry) }
                        .padding(vertical = 12.dp)
                        .testTag(entry.tag),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(entry.title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(entry.body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
    }
}

/**
 * What somebody is agreeing to before the updater will offer them an unfinished build.
 *
 * It states the one consequence that is not obvious and cannot be undone by flicking the switch
 * back: Android will not install an older version over a newer one, so a beta keeps you on betas
 * until the stable release passes it. Everything else here is a risk somebody can weigh; that is a
 * fact they would otherwise discover afterwards.
 */
@Composable
private fun BetaOptInDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_beta_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.settings_beta_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.settings_beta_dialog_export),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(SettingsBetaConfirmTag)) {
                Text(stringResource(R.string.settings_beta_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_beta_dialog_cancel))
            }
        },
    )
}

/**
 * Names this device, committed on Done and never per keystroke: the name is broadcast to everybody
 * on the Wi-Fi while a nearby screen is open, and half of one should never be. Left empty, the
 * generated name comes back.
 */
@Composable
private fun RenameDeviceDialog(
    current: String,
    generated: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(if (current == generated) "" else current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_nearby_name_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(64) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_nearby_name_dialog_hint, generated)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(text) }) {
                Text(stringResource(R.string.settings_nearby_name_dialog_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_nearby_name_dialog_cancel))
            }
        },
    )
}

/**
 * A setting with its explanation, and the switch that turns it.
 *
 * The whole row is the target, not just the switch. Reaching a 32dp control at the far edge of a
 * phone is the sort of thing that is fine in a mock-up and irritating in the hand, and the sentence
 * explaining a setting is the most natural thing to press.
 */
@Composable
internal fun SettingsSwitch(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                onValueChange = onCheckedChange,
                role = Role.Switch,
            )
            .padding(vertical = 8.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // Driven by the row, so the two cannot disagree and a screen reader hears one control.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The one part of the screen that changes: where an update attempt has got to. */
@Composable
private fun UpdatePanel(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (AvailableUpdate) -> Unit,
    onCancel: () -> Unit,
    onInstall: (java.io.File) -> Unit,
    onSkip: (AvailableUpdate) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.padding(top = 16.dp)) {
        when (state) {
            UpdateState.Disabled, UpdateState.Idle -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onCheck) {
                        Text(stringResource(R.string.settings_updates_check_now))
                    }
                }
            }

            UpdateState.Checking -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.settings_updates_checking),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is UpdateState.UpToDate -> Column {
                Text(
                    stringResource(R.string.settings_updates_current),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onCheck, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_updates_check_now))
                }
            }

            is UpdateState.Available -> UpdateCard {
                Text(
                    stringResource(R.string.settings_update_available, state.update.version.toString()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_update_size, formatBytes(state.update.sizeBytes)),
                    style = FTreeText.recordSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                state.update.notes?.let { notes ->
                    Text(
                        notes.lineSequence().take(6).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onDownload(state.update) }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.settings_update_download))
                    }
                    TextButton(onClick = { onSkip(state.update) }) {
                        Text(stringResource(R.string.settings_update_skip))
                    }
                }
            }

            is UpdateState.Downloading -> UpdateCard {
                Text(
                    stringResource(R.string.settings_update_downloading, (state.progress * 100).toInt()),
                    style = MaterialTheme.typography.titleSmall,
                )
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                TextButton(onClick = onCancel, modifier = Modifier.padding(top = 6.dp)) {
                    Text(stringResource(R.string.settings_update_cancel))
                }
            }

            is UpdateState.Ready -> UpdateCard {
                Text(
                    stringResource(R.string.settings_update_ready, state.update.version.toString()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_update_ready_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Button(
                    onClick = { onInstall(state.file) },
                    modifier = Modifier.padding(top = 14.dp),
                ) {
                    Text(stringResource(R.string.settings_update_install))
                }
            }

            is UpdateState.Failed -> UpdateCard(warning = true) {
                Text(
                    stringResource(state.failure.message()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onCheck) {
                        Text(stringResource(R.string.settings_updates_check_now))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_update_cancel))
                    }
                }
            }
        }

        Text(
            stringResource(R.string.settings_updates_keeps_data),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun UpdateCard(
    warning: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        color = if (warning) FTreeTheme.accents.unknownSurface
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SettingsAction(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.padding(top = 2.dp)) { icon() }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

private fun UpdateFailure.message(): Int = when (this) {
    UpdateFailure.NETWORK -> R.string.update_failed_network
    UpdateFailure.SERVER -> R.string.update_failed_server
    UpdateFailure.RATE_LIMITED -> R.string.update_failed_rate_limited
    UpdateFailure.NO_RELEASES -> R.string.update_failed_no_releases
    UpdateFailure.INSECURE_URL -> R.string.update_failed_insecure
    UpdateFailure.TRUNCATED -> R.string.update_failed_truncated
    UpdateFailure.STORAGE -> R.string.update_failed_storage
    UpdateFailure.CHECKSUM -> R.string.update_failed_checksum
    UpdateFailure.SIGNATURE -> R.string.update_failed_signature
    UpdateFailure.WRONG_PACKAGE -> R.string.update_failed_wrong_package
    UpdateFailure.INSTALL_NOT_PERMITTED -> R.string.update_failed_install_not_permitted
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
