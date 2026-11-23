package com.javierrb35.bibliotecaia.sync

import org.junit.Assert.*
import org.junit.Test

class ReadingPositionFileCodecTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val mobile = "mobile_12345678"
    private val tablet = "tablet_87654321"
    private fun record(hash: String, locator: String, time: Long, who: String) =
        ReadingPositionRecord(hash, locator, .42f, time, who)

    @Test fun stableRoundTripAndSeparateBooks() {
        val document = ReadingPositionDocument(mobile,
            listOf(record(b, "page:12", 31, mobile), record(a, "page:3", 42, mobile)))
        val parsed = ReadingPositionFileCodec.decode(ReadingPositionFileCodec.encode(document))
        assertEquals(mobile, parsed.deviceId)
        assertEquals(listOf(a, b), parsed.positions.map { it.contentHash })
    }

    @Test fun lastActualMovementWinsEvenIfPageGoesBack() {
        val selected = ReadingPositionFileCodec.newest(listOf(
            record(a, "page:150", 10, mobile),
            record(a, "page:20", 12, tablet),
            record(b, "page:2", 11, mobile)
        ))
        assertEquals("page:20", selected.getValue(a).locator)
        assertEquals("page:2", selected.getValue(b).locator)
    }

    @Test fun tiesResolveIdenticallyRegardlessOfReadOrder() {
        val one = record(a, "page:1", 100, mobile)
        val two = record(a, "page:2", 100, tablet)
        assertEquals(ReadingPositionFileCodec.newest(listOf(one, two)),
            ReadingPositionFileCodec.newest(listOf(two, one)))
    }

    @Test fun rejectsForeignFilesAndDuplicateBooks() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadingPositionFileCodec.decode("{}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingPositionFileCodec.decode("""{"app":"biblioteca-ia-reading-sync","version":1,"owner":"mobile_12345678","positions":[{"hash":"${a}","locator":"x","progress":0.5,"updatedAt":1,"source":"mobile_12345678"},{"hash":"${a}","locator":"y","progress":0.5,"updatedAt":2,"source":"mobile_12345678"}]}""")
        }
    }

    @Test fun rejectsInvalidProgressAndOversizedData() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadingPositionFileCodec.encode(ReadingPositionDocument(mobile,
                listOf(ReadingPositionRecord(a, "p", 1.5f, 2, mobile))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingPositionFileCodec.decode("x".repeat(ReadingPositionFileCodec.MAX_BYTES + 1))
        }
    }
}
