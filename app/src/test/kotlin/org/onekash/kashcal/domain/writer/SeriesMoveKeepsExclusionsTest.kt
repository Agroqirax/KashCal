package org.onekash.kashcal.domain.writer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * An occurrence deleted in KashCal is an EXDATE at that occurrence's start, matched by instant
 * (RFC 5545 §3.8.5.1). When the series moves (its start time, its zone, or all-day to timed),
 * [EventWriter] moves those EXDATEs with it, so the deleted day stays hidden, as other clients
 * rewrite them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SeriesMoveKeepsExclusionsTest {

    private lateinit var db: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private lateinit var writer: EventWriter
    private var calendarId = 0L
    private val savedZone: TimeZone = TimeZone.getDefault()
    private val newYork = ZoneId.of("America/New_York")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
        generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        writer = EventWriter(db, generator)
        runTest {
            val accountId = db.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local"))
            calendarId = db.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = "local://default", displayName = "Local", color = 0))
        }
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(savedZone)
    }

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private suspend fun series(start: Long, rrule: String = "FREQ=DAILY;COUNT=5", zone: String? = "America/New_York", allDay: Boolean = false): Event =
        writer.createEvent(
            Event(
                uid = "", calendarId = calendarId, title = "Series",
                startTs = start, endTs = if (allDay) start + 86_400_000L - 1 else start + hour,
                timezone = zone, isAllDay = allDay, rrule = rrule, dtstamp = 0L,
            ),
            isLocal = true,
        )

    private suspend fun deleteOn(event: Event, occurrenceStart: Long): Event {
        writer.deleteSingleOccurrence(event.id, occurrenceStart, isLocal = true)
        return db.eventsDao().getById(event.id)!!
    }

    private suspend fun shown(id: Long): List<Long> =
        db.occurrencesDao().getForEvent(id).filter { !it.isCancelled }.map { it.startTs }.sorted()

    @Test
    fun `moving the series' time keeps a deleted day hidden`() = runTest {
        val s = deleteOn(series(at(newYork, 2027, 2, 1, 10)), at(newYork, 2027, 2, 3, 10))

        writer.updateEvent(s.copy(startTs = s.startTs + hour, endTs = s.endTs + hour), isLocal = true)

        assertEquals(listOf(1, 2, 4, 5).map { at(newYork, 2027, 2, it, 11) }, shown(s.id))
        assertEquals("${at(newYork, 2027, 2, 3, 11)}", db.eventsDao().getById(s.id)!!.exdate)
    }

    @Test
    fun `changing the series' zone at the same clock time keeps a deleted day hidden`() = runTest {
        val s = deleteOn(series(at(newYork, 2027, 2, 1, 10)), at(newYork, 2027, 2, 3, 10))
        val start = at(berlin, 2027, 2, 1, 10)

        writer.updateEvent(s.copy(startTs = start, endTs = start + hour, timezone = "Europe/Berlin"), isLocal = true)

        assertEquals(listOf(1, 2, 4, 5).map { at(berlin, 2027, 2, it, 10) }, shown(s.id))
    }

    @Test
    fun `a deleted day after a clock change stays hidden when the time moves`() = runTest {
        // Mondays from 1 Mar 2027; New York moves to EDT on 14 Mar.
        val s = deleteOn(series(at(newYork, 2027, 3, 1, 10), "FREQ=WEEKLY;COUNT=4"), at(newYork, 2027, 3, 15, 10))

        writer.updateEvent(s.copy(startTs = s.startTs + hour, endTs = s.endTs + hour), isLocal = true)

        assertEquals(listOf(1, 8, 22).map { at(newYork, 2027, 3, it, 11) }, shown(s.id))
    }

    @Test
    fun `a this-and-future split with a new time keeps a later deleted day hidden`() = runTest {
        val master = deleteOn(series(at(newYork, 2027, 2, 1, 10), "FREQ=DAILY;COUNT=10"), at(newYork, 2027, 2, 6, 10))
        val splitAt = at(newYork, 2027, 2, 4, 10)
        val newStart = at(newYork, 2027, 2, 4, 11)

        val created = writer.splitSeries(master.id, splitAt, master.copy(startTs = newStart, endTs = newStart + hour), isLocal = true)

        val shownAfter = shown(created.id)
        assertFalse(shownAfter.contains(at(newYork, 2027, 2, 6, 11)))
        assertEquals(listOf(4, 5, 7, 8, 9, 10).map { at(newYork, 2027, 2, it, 11) }, shownAfter)
    }

    @Test
    fun `switching an all-day series to timed keeps a deleted day hidden`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone(berlin))
        val s = deleteOn(series(utcMidnight(2027, 2, 1), zone = null, allDay = true), utcMidnight(2027, 2, 3))
        val start = at(berlin, 2027, 2, 1, 10)

        writer.updateEvent(s.copy(isAllDay = false, startTs = start, endTs = start + hour, timezone = "Europe/Berlin"), isLocal = true)

        assertEquals(listOf(1, 2, 4, 5).map { at(berlin, 2027, 2, it, 10) }, shown(s.id))
    }

    @Test
    fun `a deleted day moved into a clock gap resolves as the occurrence does`() = runTest {
        // Daily 01:30 New York around 14 Mar 2027, when 02:00-03:00 doesn't exist.
        val s = deleteOn(series(at(newYork, 2027, 3, 12, 1, 30)), at(newYork, 2027, 3, 14, 1, 30))

        writer.updateEvent(s.copy(startTs = s.startTs + hour, endTs = s.endTs + hour), isLocal = true)

        val starts = shown(s.id)
        assertEquals(4, starts.size)
        assertFalse(starts.any { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), newYork).dayOfMonth == 14 })
    }

    @Test
    fun `moving the series' start date keeps a deleted day of a weekday rule on that day`() = runTest {
        // Mon, Wed, Fri at 10:00 from Mon 1 Feb 2027, Wed 10 Feb deleted. The start moves to
        // Tue 2 Feb and the rule keeps its days.
        val s = deleteOn(series(at(newYork, 2027, 2, 1, 10), "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=9"), at(newYork, 2027, 2, 10, 10))
        val start = at(newYork, 2027, 2, 2, 10)

        writer.updateEvent(s.copy(startTs = start, endTs = start + hour), isLocal = true)

        val days = shown(s.id).map { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), newYork).dayOfMonth }
        assertFalse(days.toString(), 10 in days)
        assertTrue(days.toString(), 12 in days)
    }

    @Test
    fun `exclusions the edit doesn't move stay as stored`() = runTest {
        // The caller set its own EXDATE.
        val a = deleteOn(series(at(newYork, 2027, 2, 1, 10)), at(newYork, 2027, 2, 3, 10))
        writer.updateEvent(a.copy(startTs = a.startTs + hour, endTs = a.endTs + hour, exdate = "${at(newYork, 2027, 2, 2, 11)}"), isLocal = true)
        assertEquals("${at(newYork, 2027, 2, 2, 11)}", db.eventsDao().getById(a.id)!!.exdate)

        // Nothing about the start moved.
        val b = deleteOn(series(at(newYork, 2027, 2, 1, 10)), at(newYork, 2027, 2, 3, 10))
        writer.updateEvent(b.copy(title = "Renamed"), isLocal = true)
        assertEquals(b.exdate, db.eventsDao().getById(b.id)!!.exdate)

        // A rule with clock parts: DTSTART's time doesn't place every occurrence.
        val c = deleteOn(series(at(newYork, 2027, 2, 1, 10), "FREQ=DAILY;BYHOUR=10,15;COUNT=6"), at(newYork, 2027, 2, 2, 15))
        writer.updateEvent(c.copy(startTs = c.startTs + hour, endTs = c.endTs + hour), isLocal = true)
        assertEquals(c.exdate, db.eventsDao().getById(c.id)!!.exdate)

        // Timed to all-day: all-day series match exclusions by date.
        val d = deleteOn(series(at(newYork, 2027, 2, 1, 10)), at(newYork, 2027, 2, 3, 10))
        writer.updateEvent(d.copy(isAllDay = true, startTs = utcMidnight(2027, 2, 1), endTs = utcMidnight(2027, 2, 2) - 1, timezone = null), isLocal = true)
        assertEquals(d.exdate, db.eventsDao().getById(d.id)!!.exdate)

        // RDATEs keep their own instants.
        val rdate = "${at(newYork, 2027, 2, 3, 15)}"
        val e = db.eventsDao().getById(series(at(newYork, 2027, 2, 1, 10)).id)!!.copy(rdate = rdate)
        db.eventsDao().update(e)
        writer.updateEvent(e.copy(startTs = e.startTs + hour, endTs = e.endTs + hour), isLocal = true)
        assertEquals(rdate, db.eventsDao().getById(e.id)!!.rdate)
    }
}
