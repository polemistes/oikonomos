package app.oikonomos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BackupSnapshotsTest {
    @Test
    fun nameCarriesTheTimeAndCanBeReadBack() {
        val time = LocalDateTime.of(2026, 11, 20, 2, 39, 7)
        assertEquals("oikonomos-2026-11-20-023907.json", BackupSnapshots.name(time))
        assertEquals(time, BackupSnapshots.time("oikonomos-2026-11-20-023907.json"))
    }

    @Test
    fun otherFilesAreNotTreatedAsSnapshots() {
        assertNull(BackupSnapshots.time("oikonomos-2026-11-20.json"))
        assertNull(BackupSnapshots.time("oikonomos-2026-13-40-999999.json"))
        assertNull(BackupSnapshots.time("notater.json"))
        assertEquals(emptyList<String>(), BackupSnapshots.toDelete(listOf("notater.json", "vedlegg")))
    }

    @Test
    fun keepsTheNewestTenAndTheLastOfEachEarlierDay() {
        // Tolv kopier i dag, tre i går og to dagen før.
        val today = (10..21).map { BackupSnapshots.name(LocalDateTime.of(2026, 11, 20, it, 0, 0)) }
        val yesterday = (8..10).map { BackupSnapshots.name(LocalDateTime.of(2026, 11, 19, it, 0, 0)) }
        val before = (8..9).map { BackupSnapshots.name(LocalDateTime.of(2026, 11, 18, it, 0, 0)) }

        val deleted = BackupSnapshots.toDelete((before + today + yesterday).shuffled())

        // De to eldste fra i dag faller utenfor de ti nyeste. Fra tidligere dager beholdes bare den siste.
        assertEquals(
            setOf(today[0], today[1], yesterday[0], yesterday[1], before[0]),
            deleted.toSet(),
        )
    }

    @Test
    fun deletesNothingWhenThereAreFewSnapshots() {
        val names = (1..10).map { BackupSnapshots.name(LocalDateTime.of(2026, 11, 20, 12, it, 0)) }
        assertTrue(BackupSnapshots.toDelete(names).isEmpty())
    }

    @Test
    fun forgetsDaysBeyondTheRetentionPeriod() {
        // Én kopi per dag i 40 dager: de 30 siste dagene beholdes.
        val names = (0L until 40).map { BackupSnapshots.name(LocalDateTime.of(2026, 11, 20, 12, 0, 0).minusDays(it)) }
        assertEquals(names.drop(30).toSet(), BackupSnapshots.toDelete(names).toSet())
    }
}
