package de.fahrerabrechnung.data.card

import org.junit.Assert.*
import org.junit.Test

class CardCoreSnapshotTest {
    @Test fun originalPointerBytesAndUnusedTailCannotBeChangedThroughInputOrOutputBuffers() {
        val raw = ByteArray(68).also { it[1] = 9; it[3] = 12; it[67] = 42 }
        val original = raw.copyOf()
        val file = CardCoreSnapshot(CardCoreEf.ACTIVITY, CardGeneration.GEN2V2, raw)
        raw.fill(0); file.bytes.fill(1)
        assertArrayEquals(original, file.bytes)
        assertEquals(9, file.activity().oldestDayOffset)
        assertEquals(12, file.activity().newestDayOffset)
        file.activity().dailyRecords.fill(0)
        assertArrayEquals(original, file.bytes)
    }

    @Test fun invalidPointersDoNotInvalidateTheCompleteOriginal() {
        for (kind in CardCoreEf.entries) {
            val raw = ByteArray(if (kind == CardCoreEf.ACTIVITY) 68 else 50).also { it[0] = -1; it[1] = -1 }
            val file = CardCoreSnapshot(kind, CardGeneration.GEN2, raw)
            try { if (kind == CardCoreEf.ACTIVITY) file.activity() else file.vehicles(); fail() }
            catch (_: ParseError) { }
            assertArrayEquals(raw, file.bytes)
            assertEquals(CardImportFileStatus.READY, CardCoreRead(kind, CardCoreReadStatus.COMPLETE, file).result().status)
        }
    }

    @Test fun onlyTheCompleteZeroDefaultWithBothZeroPointersIsAnUnusedActivityRing() {
        val raw = ByteArray(68)
        val file = CardCoreSnapshot(CardCoreEf.ACTIVITY, CardGeneration.GEN1, raw)
        assertTrue(CardActivityParser.parse(file.activity()).isEmpty())
        for (offset in listOf(1, 3, 67)) {
            val changed = raw.copyOf().also { it[offset] = 1 }
            try { CardActivityParser.parse(CardCoreSnapshot(CardCoreEf.ACTIVITY, CardGeneration.GEN1, changed).activity()); fail() }
            catch (_: ParseError) { }
        }
    }

    @Test fun unusedVehicleRecordFillerRemainsInTheOriginalForEveryGeneration() {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val width = if (generation == CardGeneration.GEN1) 31 else 48
            val raw = ByteArray(2 + width).also { it[17] = 1; it[18] = 32 }
            val file = CardCoreSnapshot(CardCoreEf.VEHICLES, generation, raw)
            assertTrue(CardVehiclesParser.parse(file.vehicles()).isEmpty())
            assertArrayEquals(raw, file.bytes)
        }
    }



    @Test fun unavailableCapacityAbsentEfAndFailedAttemptAreDistinctAndHaveNoSnapshot() {
        val unread = CardCoreRead(CardCoreEf.ACTIVITY, CardCoreReadStatus.NOT_ATTEMPTED)
        val absent = CardCoreRead(CardCoreEf.ACTIVITY, CardCoreReadStatus.ABSENT)
        val failed = CardCoreRead(CardCoreEf.ACTIVITY, CardCoreReadStatus.READ_FAILED, failure = ReaderFailure.TIMEOUT)
        assertEquals(CardImportFileStatus.NOT_ATTEMPTED, unread.result().status)
        assertEquals("CAPACITY_UNAVAILABLE", unread.result().failureCode)
        assertEquals(CardImportFileStatus.MISSING, absent.result().status)
        assertEquals("EF_ABSENT", absent.result().failureCode)
        assertEquals(CardImportFileStatus.READ_FAILED, failed.result().status)
        assertEquals("TIMEOUT", failed.result().failureCode)
        assertTrue(listOf(unread, absent, failed).all { it.snapshot == null })
    }
}

