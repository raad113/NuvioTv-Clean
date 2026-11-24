package com.nuvio.tv.core.cleanstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanStreamClientTest {

    @Test
    fun highIsTheStrictestLevel() {
        assertTrue(CleanStreamClient.meetsLevel("low", CleanStreamLevel.High))
        assertTrue(CleanStreamClient.meetsLevel("medium", CleanStreamLevel.Medium))
        assertFalse(CleanStreamClient.meetsLevel("low", CleanStreamLevel.Medium))
        assertTrue(CleanStreamClient.meetsLevel("high", CleanStreamLevel.Low))
        assertFalse(CleanStreamClient.meetsLevel("medium", CleanStreamLevel.Low))
        assertFalse(CleanStreamClient.meetsLevel("high", CleanStreamLevel.Off))
    }

    @Test
    fun overlappingSkipsAreMerged() {
        val merged = CleanStreamClient.merge(
            listOf(
                CleanSkip(1_000, 5_000, "both", "Violence"),
                CleanSkip(5_200, 9_000, "audio", "Language"),
                CleanSkip(20_000, 25_000, "both", "Nudity"),
            )
        )
        assertEquals(2, merged.size)
        assertEquals(1_000L, merged[0].startMs)
        assertEquals(9_000L, merged[0].endMs)
        assertEquals("both", merged[0].channel)
        assertEquals("Violence, Language", merged[0].label)
    }

    @Test
    fun timingChoicesCycleAndLabel() {
        assertEquals(2_000L, CleanStreamTiming.nextAfter(CleanStreamTiming.MARGINS_MS, 1_000L))
        assertEquals(0L, CleanStreamTiming.nextAfter(CleanStreamTiming.MARGINS_MS, 5_000L))
        assertEquals("+0.5 s", CleanStreamTiming.label(500L))
        assertEquals("-2 s", CleanStreamTiming.label(-2_000L))
        assertEquals("0 s", CleanStreamTiming.label(0L))
    }

    @Test
    fun levelsCycleInOrder() {
        assertEquals(CleanStreamLevel.Low, CleanStreamLevel.Off.next())
        assertEquals(CleanStreamLevel.Off, CleanStreamLevel.High.next())
    }
}
