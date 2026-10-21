package org.onekash.kashcal.sync.integration.multiserver

import android.provider.ContactsContract.CommonDataKinds.Event
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.contacts.VCardContactMapper
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.carddav.contactResourceName
import org.onekash.kashcal.sync.carddav.model.ContactDeleteResult
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.vcard.VCardWriter
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.ContactDate
import org.onekash.vcard.model.StructuredName
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Probes whether a birthday without a year reaches the birthday calendar after a server round
 * trip, per server and per wire form (#395).
 *
 * Each case creates its own contact under a fresh UID, reads back only that resource, maps it
 * with the production reader and [VCardContactMapper], runs the START_DATE through
 * [ContactEventUtils.parseContactDate], then deletes only the URL it created. Bodies are
 * synthetic (`@example.test`). Only the app-push case asserts; the hand-written forms print.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCardDavYearlessBirthdayProbeTest(
    private val config: CardDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CardDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private fun rawBody(uid: String, bdayLine: String) = buildString {
            append("BEGIN:VCARD\r\n")
            append("VERSION:3.0\r\n")
            append("UID:$uid\r\n")
            append("FN:Yearless Probe\r\n")
            append("N:Probe;Yearless;;;\r\n")
            append("EMAIL;TYPE=HOME:yearless@example.test\r\n")
            append("$bdayLine\r\n")
            append("END:VCARD\r\n")
        }
    }

    private var client: CardDavClient? = null
    private var creds: ServerCredentials? = null
    private lateinit var reader: CardDavContactReader

    @Before
    fun setup() {
        CardDavTestServerLoader.createClient(config)?.let {
            client = it.first
            creds = it.second
            reader = CardDavContactReader(it.first)
        }
    }

    @Test
    fun `yearless birthday reaches the birthday calendar after a server round trip`() = runBlocking {
        assumeTrue("${config.name}: no credentials", client != null)
        assumeTrue(
            "${config.name}: unreachable",
            CardDavTestServerLoader.isServerReachable(creds!!.davEndpoint),
        )
        val c = client!!
        val book = resolveWritableBook(c, creds!!)
        assumeTrue("${config.name}: no writable address book", book != null)

        val cases = listOf<Pair<String, (String) -> String>>(
            "app-push(--03-15)" to { uid ->
                val contact = Contact(
                    version = book!!.vcardVersion,
                    uid = uid,
                    structuredName = StructuredName(family = "Probe", given = "Yearless"),
                    displayName = "Yearless Probe",
                    birthday = ContactDate(text = "--03-15"),
                    rawVCard = "",
                )
                VCardWriter().write(contact, book.vcardVersion)
            },
            "apple-1604" to { uid ->
                rawBody(uid, "BDAY;X-APPLE-OMIT-YEAR=1604;VALUE=date:1604-03-15")
            },
            "compact(--0315)" to { uid -> rawBody(uid, "BDAY:--0315") },
        )

        val failures = mutableListOf<String>()
        for ((label, bodyFor) in cases) {
            val uid = "kashcal-yearless-probe-${UUID.randomUUID()}"
            val body = bodyFor(uid)
            val sentBday = unfold(body).lines().filter { it.startsWith("BDAY") }
            val resourceUrl = book!!.url.trimEnd('/') + "/" + contactResourceName(uid)
            val put = c.putContact(resourceUrl, body, ContactPrecondition.IfAbsent)
            if (put !is ContactUploadResult.Success) {
                println("=== ${config.name} [$label] PUT refused: $put")
                continue
            }
            val ownUrl = put.finalUrl ?: resourceUrl
            try {
                val ownName = contactResourceName(uid)
                val listed = (c.listAllContactHrefs(book.url) as? CalDavResult.Success)?.data.orEmpty()
                val href = listed.firstOrNull { it.first.substringAfterLast('/') == ownName }?.first
                if (href == null) {
                    println("=== ${config.name} [$label] own resource not listed after PUT")
                    continue
                }
                val raw = (c.fetchContactsByHref(book.url, listOf(href), book.vcardVersion) as? CalDavResult.Success)
                    ?.data?.firstOrNull()?.vcardBody
                val servedBday = raw?.let { unfold(it).lines().filter { l -> l.startsWith("BDAY") } }
                val contact = (reader.readContacts(book.url, listOf(href), book.vcardVersion) as? CalDavResult.Success)
                    ?.data?.contacts?.firstOrNull { it.contact.uid == uid }?.contact
                val startDate = contact?.let {
                    VCardContactMapper.toEntity(it).dataRows
                        .firstOrNull { r -> r.getAsString("mimetype") == Event.CONTENT_ITEM_TYPE }
                        ?.getAsString(Event.START_DATE)
                }
                val parsed = ContactEventUtils.parseContactDate(startDate)
                println(
                    "=== ${config.name} [$label] book v${book.vcardVersion}: sent=$sentBday " +
                        "served=$servedBday parsed=${contact?.birthday} START_DATE=$startDate " +
                        "calendar=${parsed ?: "DROPPED"}",
                )
                if (label.startsWith("app-push") && parsed == null) {
                    failures += "$label served=$servedBday START_DATE=$startDate"
                }
            } finally {
                val etag = put.etag ?: currentEtag(c, book.url, uid)
                if (etag != null) {
                    val deleted = c.deleteContact(ownUrl, etag)
                    if (deleted !is ContactDeleteResult.Deleted && deleted !is ContactDeleteResult.AlreadyGone) {
                        println("=== ${config.name} [$label] cleanup delete: $deleted (url ends ${ownUrl.substringAfterLast('/')})")
                    }
                } else {
                    println("=== ${config.name} [$label] cleanup skipped: no etag for own resource")
                }
            }
        }
        assertTrue("${config.name}: yearless birthday dropped: $failures", failures.isEmpty())
    }

    private fun unfold(s: String) = s.replace(Regex("""\r?\n[ \t]"""), "").replace("\r", "")

    private suspend fun currentEtag(c: CardDavClient, bookUrl: String, uid: String): String? {
        val name = contactResourceName(uid)
        val listed = (c.listAllContactHrefs(bookUrl) as? CalDavResult.Success)?.data.orEmpty()
        return listed.firstOrNull { it.first.substringAfterLast('/') == name }?.second
    }

    private suspend fun resolveWritableBook(c: CardDavClient, cr: ServerCredentials) = run {
        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).getOrNull() ?: return@run null
        val homes = (c.discoverAddressBookHome(principal) as? CalDavResult.Success)?.data.orEmpty()
        if (homes.isEmpty()) return@run null
        val books = (c.listAddressBooks(homes.first()) as? CalDavResult.Success)?.data.orEmpty()
        books.firstOrNull { !it.isReadOnly }
    }
}
