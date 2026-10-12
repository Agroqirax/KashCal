package org.onekash.kashcal.testutil

import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.IcsImportFile
import org.onekash.kashcal.data.ics.ImportGroup
import org.onekash.kashcal.data.ics.ImportedComponent

/**
 * Builds an import file without METHOD or attendees from [events], grouped by UID the way
 * `IcsParserService.parseForImport` groups a file: masters, and exceptions by their
 * originalInstanceTime.
 */
fun plainImportFile(events: List<Event>): IcsImportFile = IcsImportFile(
    method = null,
    groups = events.groupBy { it.uid }.map { (uid, group) ->
        ImportGroup(
            uid = uid,
            masters = group.filter { it.originalInstanceTime == null }.map { ImportedComponent(it, emptyList()) },
            exceptions = group.filter { it.originalInstanceTime != null }.map { ImportedComponent(it, emptyList()) },
        )
    },
)
