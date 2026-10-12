package org.onekash.kashcal.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.ics.GroupPlan

/** Tests [IcsImportSheetState]: one preview read per calendar picked, and what shows meanwhile. */
class IcsImportSheetStateTest {

    private val plan = GroupPlan("u", emptyList(), isInvitation = true, userIsOrganizer = false, userIsAttendee = true)

    @Test
    fun `each calendar picked reads its own preview`() = runTest {
        val asked = mutableListOf<Pair<Long, Boolean>>()
        val state = IcsImportSheetState { id, device -> asked += id to device; listOf(plan) }

        state.select(1L, isDevice = false)
        state.select(7L, isDevice = true)

        assertEquals(listOf(1L to false, 7L to true), asked)
        assertEquals(ImportPreviewState.Ready(listOf(plan)), state.preview)
    }

    @Test
    fun `the preview shows Loading until the read returns`() = runTest {
        val gate = CompletableDeferred<List<GroupPlan>>()
        val state = IcsImportSheetState { _, _ -> gate.await() }

        launch { state.select(1L, isDevice = false) }
        runCurrent()
        assertEquals(ImportPreviewState.Loading, state.preview)

        gate.complete(listOf(plan))
        runCurrent()
        assertEquals(ImportPreviewState.Ready(listOf(plan)), state.preview)
    }

    @Test
    fun `a read that fails shows Failed`() = runTest {
        val state = IcsImportSheetState { _, _ -> error("provider gone") }

        state.select(1L, isDevice = false)

        assertEquals(ImportPreviewState.Failed, state.preview)
    }
}
