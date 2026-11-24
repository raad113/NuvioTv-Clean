package com.nuvio.tv.core.cleanstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleProfanityTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,500
        Good morning, everyone.

        2
        00:01:02,250 --> 00:01:04,000
        <i>What the fuck is that?</i>

        3
        00:01:10,000 --> 00:01:12,000
        I grew up in Scunthorpe.

        4
        01:02:03,004 --> 01:02:05,000
        يا شرموط
    """.trimIndent()

    @Test
    fun parsesSrtTimes() {
        val cues = SubtitleProfanity.parseCues(srt)
        assertEquals(4, cues.size)
        assertEquals(62_250L, cues[1].startMs)
        assertEquals(64_000L, cues[1].endMs)
        assertEquals(3_723_004L, cues[3].startMs)
    }

    @Test
    fun parsesWebVtt() {
        val vtt = "WEBVTT\n\n00:05.000 --> 00:07.500\nOh shit!\n"
        val cues = SubtitleProfanity.parseCues(vtt)
        assertEquals(1, cues.size)
        assertEquals(5_000L, cues[0].startMs)
        assertEquals(1, SubtitleProfanity.findMutes(cues, "").size)
    }

    @Test
    fun findsEnglishAndArabicWithoutFalseMatches() {
        val mutes = SubtitleProfanity.findMutes(SubtitleProfanity.parseCues(srt), "")
        // the English line and the Arabic line, but not "Scunthorpe" or the greeting
        assertEquals(listOf(62_250L, 3_723_004L), mutes.map { it.startMs })
        assertTrue(mutes.all { it.channel == "audio" })
    }

    @Test
    fun customWordsAreWholeWords() {
        val cues = SubtitleProfanity.parseCues("1\n00:00:01,000 --> 00:00:02,000\nOh darn it\n\n2\n00:00:03,000 --> 00:00:04,000\nDarnell is here\n")
        val mutes = SubtitleProfanity.findMutes(cues, "darn, heck")
        assertEquals(1, mutes.size)
        assertEquals(1_000L, mutes[0].startMs)
        assertFalse(SubtitleProfanity.buildPattern("").matcher("hello there").find())
    }
}
