package com.vibethroughcode.ftree.ui.more

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.kutumb.treesync.MergeSuggestion

const val MergeReviewTag = "merge-review"
const val MergeNoThanksTag = "merge-no-thanks"

/**
 * The one question the offer asks. Yes opens the same review a file import uses, where every likely match is
 * shown and nothing changes until it is confirmed; no stops the offer for that tree.
 */
@Composable
fun MergeOfferDialog(offer: MergeSuggestion.Candidate, onReview: () -> Unit, onNoThanks: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_merge_title, offer.name)) },
        text = { Text(stringResource(R.string.merge_dialog_body, offer.name)) },
        confirmButton = {
            Button(onClick = onReview, modifier = Modifier.testTag(MergeReviewTag)) { Text(stringResource(R.string.merge_review)) }
        },
        dismissButton = {
            TextButton(onClick = onNoThanks, modifier = Modifier.testTag(MergeNoThanksTag)) { Text(stringResource(R.string.merge_no_thanks)) }
        },
    )
}
