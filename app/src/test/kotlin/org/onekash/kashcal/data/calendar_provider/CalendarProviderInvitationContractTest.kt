package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Asserts the repository calls an ICS invitation import makes: finding rows by their iCalendar
 * UID (`UID_2445`), creating an invitation with its organizer and guests as given, a plain
 * create carrying a UID, and a whole-event status write. The same assertions run against
 * [FakeCalendarProviderRepository] and against the real repository over
 * [SqliteCalendarProvider], so the import tests on the fake can't drift from the real SQL.
 */
abstract class CalendarProviderInvitationContractTest {

    protected abstract val repo: CalendarProviderRepository

    /** Seeds a row with [uid] in [calendarId], as a sync adapter stores it. */
    protected abstract fun seedUidRow(uid: String, calendarId: Long, deleted: Boolean = false, originalId: Long? = null): Long

    protected data class Guest(val email: String, val relationship: Int, val status: Int)

    protected abstract fun guestsOf(eventId: Long): List<Guest>
    protected abstract fun organizerOf(eventId: Long): String?

    private suspend fun invite(uid: String = UID) = repo.createInvitation(
        calendarId = CAL_ID, title = "Planning", description = null, location = null,
        startTs = START, endTs = START + HOUR, isAllDay = false, rrule = null, duration = null,
        timezone = "UTC", reminders = listOf(15), uid = uid, organizer = "boss@example.test",
        status = Events.STATUS_CONFIRMED,
        attendees = listOf(
            DeviceAttendee(0, "Boss", "boss@example.test", Attendees.RELATIONSHIP_ORGANIZER, Attendees.ATTENDEE_STATUS_ACCEPTED),
            DeviceAttendee(0, "Me", OWNER, Attendees.RELATIONSHIP_ATTENDEE, Attendees.ATTENDEE_STATUS_INVITED),
            DeviceAttendee(0, "No mail", null, Attendees.RELATIONSHIP_ATTENDEE, Attendees.ATTENDEE_STATUS_INVITED),
        ),
    ).getOrThrow()

    @Test
    fun `an invitation keeps its UID, organizer and guests as given`() = runTest {
        val id = invite()

        val row = repo.findEventsByUid(UID, listOf(CAL_ID)).getOrThrow().single()
        assertEquals(id, row.eventId)
        assertEquals(Events.STATUS_CONFIRMED, row.status)
        assertEquals("boss@example.test", organizerOf(id))
        assertEquals(
            "no owner organizer row is added, and a guest without an email is skipped",
            listOf(
                Guest("boss@example.test", Attendees.RELATIONSHIP_ORGANIZER, Attendees.ATTENDEE_STATUS_ACCEPTED),
                Guest(OWNER, Attendees.RELATIONSHIP_ATTENDEE, Attendees.ATTENDEE_STATUS_INVITED),
            ),
            guestsOf(id).sortedBy { it.email },
        )
    }

    @Test
    fun `a UID lookup returns the live rows of the given calendars`() = runTest {
        val master = seedUidRow(UID, CAL_ID)
        val exception = seedUidRow(UID, CAL_ID, originalId = master)
        seedUidRow(UID, CAL_ID, deleted = true)
        val other = seedUidRow(UID, OTHER_CAL_ID)
        seedUidRow("another@example.test", CAL_ID)

        val rows = repo.findEventsByUid(UID, listOf(CAL_ID)).getOrThrow()

        assertEquals(setOf(master, exception), rows.map { it.eventId }.toSet())
        assertEquals(master, rows.single { it.eventId == exception }.originalId)
        assertEquals(setOf(master, exception, other), repo.findEventsByUid(UID, listOf(CAL_ID, OTHER_CAL_ID)).getOrThrow().map { it.eventId }.toSet())
        assertTrue(repo.findEventsByUid(UID, emptyList()).getOrThrow().isEmpty())
    }

    @Test
    fun `a plain create stores a UID only when given one`() = runTest {
        suspend fun create(uid: String?) = repo.createEvent(
            calendarId = CAL_ID, title = "Plain", description = null, location = null, startTs = START,
            endTs = START + HOUR, isAllDay = false, rrule = null, duration = null, timezone = "UTC",
            reminders = emptyList(), uid = uid,
        ).getOrThrow()

        val withUid = create(UID)
        create(null)

        assertEquals(listOf(withUid), repo.findEventsByUid(UID, listOf(CAL_ID)).getOrThrow().map { it.eventId })
    }

    @Test
    fun `a status write cancels the whole event`() = runTest {
        val id = invite()

        repo.setEventStatus(id, Events.STATUS_CANCELED).getOrThrow()

        assertEquals(Events.STATUS_CANCELED, repo.findEventsByUid(UID, listOf(CAL_ID)).getOrThrow().single().status)
    }

    companion object {
        const val CAL_ID = 21L
        const val OTHER_CAL_ID = 22L
        const val UID = "planning@example.test"
        const val OWNER = "me@example.test"
        const val START = 1_792_317_600_000L
        const val HOUR = 3_600_000L
    }
}

/** The contract on [FakeCalendarProviderRepository]. */
class FakeCalendarProviderInvitationContractTest : CalendarProviderInvitationContractTest() {
    private val fake = FakeCalendarProviderRepository()
    private var nextId = 700L
    override val repo: CalendarProviderRepository get() = fake

    override fun seedUidRow(uid: String, calendarId: Long, deleted: Boolean, originalId: Long?): Long {
        val id = nextId++
        fake.uidRows[id] = DeviceUidRow(id, calendarId, uid, originalId, originalId?.let { START }, null, Events.STATUS_CONFIRMED, false)
        if (deleted) fake.softDeletedEventIds += id
        return id
    }

    override fun guestsOf(eventId: Long): List<Guest> =
        fake.deviceAttendees[eventId].orEmpty().map { Guest(it.email!!, it.relationship, it.status) }

    override fun organizerOf(eventId: Long): String? = fake.uidRows[eventId]?.organizer
}

/** The contract on the real [AndroidCalendarProviderRepository] over real SQL. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidCalendarProviderInvitationContractTest : CalendarProviderInvitationContractTest() {
    private lateinit var provider: SqliteCalendarProvider
    private lateinit var real: AndroidCalendarProviderRepository
    override val repo: CalendarProviderRepository get() = real

    @Before
    fun installProvider() {
        provider = SqliteCalendarProvider.install()
        real = SqliteCalendarProvider.repository(ApplicationProvider.getApplicationContext())
        provider.seedCalendar(CAL_ID, accountName = OWNER)
        provider.seedCalendar(OTHER_CAL_ID, accountName = OWNER)
    }

    @After
    fun closeProvider() = provider.db.close()

    override fun seedUidRow(uid: String, calendarId: Long, deleted: Boolean, originalId: Long?): Long =
        provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to calendarId,
            Events.TITLE to "Seeded",
            Events.DTSTART to START,
            Events.DTEND to START + HOUR,
            Events.EVENT_TIMEZONE to "UTC",
            Events.UID_2445 to uid,
            Events.DELETED to if (deleted) 1 else 0,
            Events.ORIGINAL_ID to originalId,
            Events.ORIGINAL_INSTANCE_TIME to originalId?.let { START },
        )

    override fun guestsOf(eventId: Long): List<Guest> =
        provider.rows(SqliteCalendarProvider.ATTENDEES, "${Attendees.EVENT_ID} = ?", eventId.toString()).map {
            Guest(it[Attendees.ATTENDEE_EMAIL]!!, it[Attendees.ATTENDEE_RELATIONSHIP]!!.toInt(), it[Attendees.ATTENDEE_STATUS]!!.toInt())
        }

    override fun organizerOf(eventId: Long): String? =
        provider.rows(SqliteCalendarProvider.EVENTS, "${Events._ID} = ?", eventId.toString()).single()[Events.ORGANIZER]
}
