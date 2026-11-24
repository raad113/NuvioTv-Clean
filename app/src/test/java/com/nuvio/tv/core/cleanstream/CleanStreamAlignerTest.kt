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
    private fun toOther(t: Long): Long {
        val extra = if (t >= 40 * 60_000L) 30_000L else 0L
        return ((t + extra) * (23.976 / 25.0)).toLong() + 5_000L
    }

    private val scenes = listOf(20 * 60_000L, 39 * 60_000L, 41 * 60_000L, 65 * 60_000L, 90 * 60_000L)

    private fun assertLinedUp(local: TimingRef, ref: TimingRef) {
        for (scene in scenes) {
            val transform = CleanStreamAligner.transformAt(local, ref, scene)
            assertNotNull("scene at $scene should be found", transform)
            val expected = toOther(scene)
            val actual = transform!!.map(scene)
            assertTrue("scene at $scene: expected $expected, got $actual", abs(actual - expected) <= 700)
        }
    }

    @Test
    fun skipsMoveToTheSameSceneInAnotherRelease() {
        val original = film()
        assertLinedUp(
            local = TimingRef("embedded", null, original.map { (s, e) -> toOther(s) to toOther(e) }),
            ref = TimingRef("embedded", null, original),
        )
    }

    @Test
    fun aDifferentSubtitleFileForTheOtherReleaseStillWorks() {
        // 15% of lines missing and every line up to 0.2 s off, as with another subtitle file
        val random = Random(3)
        val original = film()
        val other = original
            .filter { random.nextDouble() > 0.15 }
            .map { (s, e) ->
                val jitter = random.nextLong(-200, 200)
                (toOther(s) + jitter) to (toOther(e) + jitter)
            }
        assertLinedUp(local = TimingRef("subtitle", null, other), ref = TimingRef("embedded", null, original))
    }

    @Test
    fun anUnrelatedFilmIsNotMatched() {
        val ref = TimingRef("embedded", null, film(seed = 7))
        val unrelated = TimingRef("embedded", null, film(seed = 99))
        assertNull(CleanStreamAligner.transformAt(unrelated, ref, 30 * 60_000L))
    }

    @Test
    fun theSameCopyIsLeftAlone() {
        val ref = TimingRef("embedded", null, film())
        assertEquals(1_234_567L, CleanStreamAligner.transformAt(ref, ref.copy(), 1_234_567L)!!.map(1_234_567L))
    }

    @Test
    fun frameRateIsRecognisedFromLengthsAlone() {
        val film = 7_200_000L
        val pal = (film * 23.976 / 25.0).toLong()
        val transform = CleanStreamAligner.frameRateTransform(pal, film)
        assertNotNull(transform)
        assertEquals(3_600_000.0 * 23.976 / 25.0, transform!!.map(3_600_000L).toDouble(), 50.0)
        // Same length, or a difference that is not a frame-rate change: no change
        assertNull(CleanStreamAligner.frameRateTransform(film, film))
        assertNull(CleanStreamAligner.frameRateTransform(film + 120_000L, film))
    }
}
