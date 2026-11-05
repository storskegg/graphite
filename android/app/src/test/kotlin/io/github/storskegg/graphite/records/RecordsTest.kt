package io.github.storskegg.graphite.records

import io.github.storskegg.graphite.ffi.FileDigest
import io.github.storskegg.graphite.ffi.Manifest
import io.github.storskegg.graphite.ffi.ManifestEntry
import io.github.storskegg.graphite.ffi.MetadataChoice
import io.github.storskegg.graphite.ffi.PlanStatus
import io.github.storskegg.graphite.ffi.RecordEntry
import io.github.storskegg.graphite.media.Inventory
import io.github.storskegg.graphite.media.Options
import io.github.storskegg.graphite.media.Tally
import io.github.storskegg.graphite.util.BinWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** SHA-256 stands in for BLAKE3 off the phone: the tests only need a hash. */
private val identity = Identity { MessageDigest.getInstance("SHA-256").digest(it) }

private fun fields(kind: Kind, vararg rows: Row) = identity.fields(kind, rows.toList()).map { String(it) }

private fun sms(address: String, body: String, read: Long = 1) =
    Row("sms", "address" to address, "body" to body, "date" to 1577955845123L, "type" to 1L, "read" to read)

class PayloadTest {
    @Test fun roundTripsEveryValueType() {
        val rows = listOf(Row("part", "seq" to 0L, "ct" to "image/png", Identity.DATA_BYTES to byteArrayOf(1, 2, 3)), sms("+15550100", "hi"))
        val b = Payload.encode(rows)
        assertEquals(rows, Payload.decode(b))
        assertTrue(b.contentEquals(Payload.encode(Payload.decode(b))))
    }

    @Test fun columnsAreSortedAndNullsLeftOut() {
        val a = Row("sms", "body" to "x", "address" to "+1", "subject" to null)
        val b = Row("sms", "address" to "+1", "body" to "x")
        assertTrue(a.encoded().contentEquals(b.encoded()))
        assertEquals(listOf("address", "body"), a.cols.keys.toList())
    }

    @Test(expected = IllegalStateException::class)
    fun trailingBytesAreRefused() {
        Payload.decode(Payload.encode(listOf(sms("+1", "x"))) + byteArrayOf(0))
    }

    @Test fun canonicalOrderDoesNotDependOnTheCursor() {
        val a = Row("addr", "address" to "+15550100", "type" to 137L)
        val b = Row("addr", "address" to "insert-address-token", "type" to 151L)
        assertEquals(Providers.canonical(listOf(a, b)), Providers.canonical(listOf(b, a)))
    }
}

class IdentityTest {
    @Test fun numbers() {
        assertEquals("+15550100", Identity.number("+1 (555) 010-0"))
        assertEquals("15550100", Identity.number("1-555-0100"))
        assertEquals("amazon", Identity.number(" AMAZON "))
        assertEquals("", Identity.number(null))
        assertEquals("jane doe", Identity.name("  Jane\n  DOE "))
    }

    @Test fun smsIsTheSameWhateverTheNumberFormatOrReadState() {
        assertEquals(fields(Kind.SMS, sms("+15550100", "hi")), fields(Kind.SMS, sms("+1 555-0100", "hi", read = 0)))
        assertNotEquals(fields(Kind.SMS, sms("+15550100", "hi")), fields(Kind.SMS, sms("+15550100", "hi!")))
        assertNotEquals(fields(Kind.SMS, sms("+15550100", "hi")), fields(Kind.SMS, sms("5550100", "hi")))
    }

    @Test fun mmsPartsInAnyOrderImagesByTheirBytes() {
        val pdu = Row(Identity.MMS, "msg_box" to 1L, "date" to 1578020645L, "sub" to "GT")
        val from = Row(Identity.ADDR, "address" to "+15550100", "type" to 137L)
        val png = Row(Identity.PART, "seq" to 0L, "ct" to "image/png", Identity.DATA_BYTES to byteArrayOf(9, 9))
        val text = Row(Identity.PART, "seq" to 0L, "ct" to "text/plain", "text" to "hello")
        assertEquals(fields(Kind.MMS, pdu, from, png, text), fields(Kind.MMS, pdu, text, from, png))
        val otherPng = Row(Identity.PART, "seq" to 0L, "ct" to "image/png", Identity.DATA_BYTES to byteArrayOf(9, 8))
        assertNotEquals(fields(Kind.MMS, pdu, from, png, text), fields(Kind.MMS, pdu, from, otherPng, text))
        assertEquals(1578020645000L, identity.date(Kind.MMS, listOf(pdu, from)))
        assertEquals(listOf("+15550100"), identity.parties(listOf(pdu, from, Row(Identity.ADDR, "address" to "insert-address-token"))))
    }

    @Test fun contactsByNameNumbersAndEmailsInAnyOrder() {
        fun d(mime: String, v: String) = Row(Identity.CONTACT_DATA, "mimetype" to mime, "data1" to v)
        val a = fields(Kind.CONTACT, d(Identity.NAME, "GT Jane Doe"), d(Identity.PHONE, "+1 555 0100"), d(Identity.EMAIL, "Jane@Example.org"),
            d(Identity.PHONE, "+15550101"))
        val b = fields(Kind.CONTACT, d(Identity.PHONE, "+15550101"), d(Identity.EMAIL, "jane@example.org"), d(Identity.PHONE, "+15550100"),
            d(Identity.NAME, "gt  jane doe"), d("vnd.android.cursor.item/note", "a note"))
        assertEquals(a, b)
        assertNotEquals(a, fields(Kind.CONTACT, d(Identity.NAME, "GT Jane Doe"), d(Identity.PHONE, "+15550100")))
    }

    @Test fun labelsGroupByKind() {
        assertTrue(identity.label(Kind.SMS, listOf(sms("+15550100", "hi"))).startsWith("Messages/+15550100 2020-01-0"))
        assertEquals("Blocked numbers/+15550100", identity.label(Kind.BLOCKED, listOf(Row("blocked", "original_number" to "+15550100"))))
        assertEquals("Contacts/GT Jane", identity.label(Kind.CONTACT,
            listOf(Row(Identity.RAW), Row(Identity.CONTACT_DATA, "mimetype" to Identity.NAME, "data1" to "GT Jane"))))
    }

    @Test fun testScopeIsTheFictionalRangeAndGtContacts() {
        assertTrue(identity.inTestScope(Kind.SMS, listOf(sms("+1 555 0100", "x"))))
        assertFalse(identity.inTestScope(Kind.SMS, listOf(sms("+15551234", "x"))))
        assertFalse(identity.inTestScope(Kind.CALL, listOf(Row("call", "number" to "5550100"))))
        assertTrue(identity.inTestScope(Kind.CONTACT, listOf(Row(Identity.CONTACT_DATA, "mimetype" to Identity.NAME, "data1" to "GT Bob"))))
        assertFalse(identity.inTestScope(Kind.CONTACT, listOf(Row(Identity.CONTACT_DATA, "mimetype" to Identity.NAME, "data1" to "Gtx Bob"))))
    }
}

class RecordWireTest {
    @Test fun categoriesRoundTripAsAMask() {
        val cs = setOf(Category.MESSAGES, Category.BLOCKED)
        assertEquals(cs, Category.of(Category.mask(cs)))
        val o = Options(MetadataChoice.KEEP_ALL, false, cs)
        assertEquals(o, Options.decode(o.encode()))
    }

    @Test fun anOlderPhonesMessagesStillDecode() {
        // Options and inventory from a build without records: no categories, no counts.
        assertEquals(Options(MetadataChoice.KEEP_ALL, true), Options.decode(BinWriter().u8(0).bool(true).finish()))
        val inv = Inventory(Tally(1, 2), Tally(), Tally(), Tally(), Tally(), "graphite-test", RecordCounts(3, 4, 5, -1))
        assertEquals(inv, Inventory.decode(inv.encode()))
        val old = inv.encode().let { it.copyOf(it.size - 32) }
        assertEquals(inv.copy(records = RecordCounts()), Inventory.decode(old))
    }

    @Test fun recordsFollowTheFirstWithTheirIdentity() {
        val d = FileDigest(10u, ByteArray(32) { 1 })
        fun rec(id: Int) = RecordEntry(Kind.SMS.ffi, "Messages/x", null, ByteArray(32) { id.toByte() }, d)
        val m = Manifest(MetadataChoice.KEEP_ALL, "1", listOf(ManifestEntry("DCIM/a.jpg", "image/jpeg", null, null, 0, d, d)),
            listOf(rec(1), rec(2), rec(1)))
        val p = Entries.primaries(m, listOf(PlanStatus.TO_COPY, PlanStatus.TO_COPY, PlanStatus.PRESENT, PlanStatus.TO_COPY))
        assertEquals(mapOf(0 to 0, 1 to 1, 3 to 1), p)
        assertEquals(listOf("DCIM/a.jpg", "Messages/x", "Messages/x", "Messages/x"), Entries.names(m))
    }
}
