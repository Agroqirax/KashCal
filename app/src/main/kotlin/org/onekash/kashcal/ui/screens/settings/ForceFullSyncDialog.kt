package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.onekash.kashcal.R

/**
 * Asks before a forced full sync, which also runs
 * [org.onekash.kashcal.data.db.dao.PendingOperationsDao.resetAllFailed]. [onConfirm] runs the
 * sync; both buttons close the dialog through [onDismiss].
 */
@Composable
fun ForceFullSyncDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_force_sync_title)) },
        text = { Text(stringResource(R.string.dialog_force_sync_message)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) {
                Text(stringResource(R.string.action_sync_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
