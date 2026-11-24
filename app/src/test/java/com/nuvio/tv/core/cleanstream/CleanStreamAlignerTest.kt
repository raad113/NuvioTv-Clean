package com.nuvio.tv.core.cleanstream

import com.nuvio.tv.ui.screens.player.CleanStreamAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Lining skips up between two copies of the same (made-up) film. */
class CleanStreamAlignerTest {

    /** A film's subtitle line times: lines of 1-5 s with gaps of 0.3-8 s. */
    private fun film(seed: Int = 7, lines: Int = 1_200): List<Pair<Long, Long>> {
        val random = Random(seed)
        var t = 30_000L
        return List(lines) {
            val start = t
            val end = start + random.nextLong(1_000, 5_000)
            t = end + random.nextLong(300, 8_000)
            start to end
        }
    }

    /**
     * The same film as another release: 25 fps instead of 23.976 (4% faster),
     * 5 s of extra logo at the start, and a 30 s scene added at 40 minutes
     * (times are in the original copy).
     */
    private fun otherRelease(original: List<Pair<Long, Long>>): List<Pair<Long, Long>> =
        original.map { (s, e) -> toOther(s) to toOther(e) }

    private fun toOther(t: Long): Long {
        val extra = if (t >= 40 * 60_000L) 30_000L else 0L
        return ((t + extra) * (23.976 / 25.0)).toLong() + 5_000L
    }

    @Test
    fun skipsMoveToTheSameSceneInAnotherRelease() {
        val original = film()
        val ref = TimingRef("embedded", durationMs = null, cues = original)
        val local = TimingRef("embedded", durationMs = null, cues = otherRelease(original))
        val map = CleanStreamAligner.mapperFor(local, ref)
        assertNotNull("the two releases should be matched", map)

        // Scenes before and after the added scene
        for (sceneStart in listOf(20 * 60_000L, 65 * 60_000L, 90 * 60_000L)) {
            val expected = toOther(sceneStart)
            val actual = map!!(sceneStart)
            assertTrue("scene at $sceneStart: expected $expected, got $actual", abs(actual - expected) <= 1_000)
        }
    }

    @Test
    fun theSameCopyIsLeftAlone() {
        val cues = film()
        val ref = TimingRef("embedded", null, cues)
        val map = CleanStreamAligner.mapperFor(ref, ref.copy())
        assertEquals(1_234_567L, map!!(1_234_567L))
    }

    @Test
    fun frameRateIsRecognisedFromLengthsAlone() {
        val film = 7_200_000L
        val pal = (film * 23.976 / 25.0).toLong()
        val map = CleanStreamAligner.frameRateMapper(pal, film)
        assertNotNull(map)
        assertEquals((3_600_000L * 23.976 / 25.0), map!!(3_600_000L).toDouble(), 50.0)
        // Same length, or a difference that is not a frame-rate change: no change
        assertNull(CleanStreamAligner.frameRateMapper(film, film))
        assertNull(CleanStreamAligner.frameRateMapper(film + 120_000L, film))
    }
}
