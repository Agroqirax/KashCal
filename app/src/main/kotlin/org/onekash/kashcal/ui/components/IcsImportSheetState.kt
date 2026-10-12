package org.onekash.kashcal.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.onekash.kashcal.domain.ics.GroupPlan
import kotlin.coroutines.cancellation.CancellationException

/** The import sheet's preview for the chosen calendar. */
sealed interface ImportPreviewState {
    data object Loading : ImportPreviewState
    data class Ready(val plans: List<GroupPlan>) : ImportPreviewState

    /** The preview couldn't be read; Import stays available, since the import itself still runs. */
    data object Failed : ImportPreviewState
}

/**
 * Holds the import sheet's preview and reloads it for each calendar the user picks.
 *
 * @param loadPreview reads the plan for a calendar without writing; the caller passes
 *   `EventCoordinator.previewIcsImport` or `DeviceEventReader.previewIcsImport` bound to the file
 */
class IcsImportSheetState(
    private val loadPreview: suspend (calendarId: Long, isDevice: Boolean) -> List<GroupPlan>,
) {
    var preview: ImportPreviewState by mutableStateOf(ImportPreviewState.Loading)
        private set

    /** Shows Loading, then the preview for [calendarId], or Failed when it can't be read. */
    suspend fun select(calendarId: Long, isDevice: Boolean) {
        preview = ImportPreviewState.Loading
        preview = try {
            ImportPreviewState.Ready(loadPreview(calendarId, isDevice))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ImportPreviewState.Failed
        }
    }
}
