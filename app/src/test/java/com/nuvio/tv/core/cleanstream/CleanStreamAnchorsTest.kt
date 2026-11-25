package com.nuvio.tv.core.cleanstream

import com.nuvio.tv.core.cleanstream.SubtitleProfanity.Cue
import com.nuvio.tv.ui.screens.player.CleanStreamAnchors
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** A film whose skip data was timed on another release than the English subtitle. */
class CleanStreamAnchorsTest {
    private val swears = listOf("What the hell?", "Goddamn son of a bitch.", "Shit, shit, shit!", "Get the hell out of here!",
        "You son of a bitch!", "Holy shit.", "Damn it, John!", "Go to hell.", "This is bullshit.", "What the hell is that?",
        "Oh my god.", "Damn you!", "Shut the hell up!", "Son of a bitch!", "Hell no.", "That's a damn shame.")
    private val filler = listOf("We need to move.", "Where is she?", "I don't know.", "Come on!", "Listen to me.", "Okay.",
        "He's coming back.", "Look out!", "Are you sure?", "Not yet.", "Keep going.", "Thank you.")
    private fun censor(s: String) = s.replace(Regex("(?i)\\b(hell|damn|goddamn|shit|bitch|bullshit)\\b")) { m ->
        m.value.mapIndexed { i, c -> if (i in 1 until m.value.length - 1 && i % 2 == 1) '*' else c }.joinToString("") }

    @Test fun skipsFollowTheDialogue() {
        var found = 0; var missing = 0; var wrong = 0; var wrongOther = 0
        for (seed in 1..10) {
            val rnd = Random(seed)
            var t = 30_000L
            val cues = mutableListOf<Cue>()
            val anchors = mutableListOf<DialogueAnchor>()
            // The skip data's copy runs at 25 fps with an extra 7 s logo, relative to the subtitle
            val toData = { x: Long -> ((x - 7_000) * (25.0 / 23.976)).toLong() }
            repeat(1_100) {
                val s = t; val e = s + rnd.nextLong(1_000, 4_000)
                val isSwear = rnd.nextDouble() < 0.04
                val text = if (isSwear) swears[rnd.nextInt(swears.size)] else filler[rnd.nextInt(filler.size)] + " " + rnd.nextInt(1000)
                cues += Cue(s, e, text)
                // The data quotes the line at the moment of the word (somewhere inside the line)
                if (isSwear && rnd.nextDouble() < 0.8) anchors += DialogueAnchor(toData(s + rnd.nextLong(0, e - s)), censor(text))
                t = e + rnd.nextLong(300, 8_000)
            }
            val matches = CleanStreamAnchors.match(anchors, cues)
            // Anchors from another film's data (random times) must never move a scene
            val shuffled = CleanStreamAnchors.match(anchors.map { DialogueAnchor(rnd.nextLong(0, 6_000_000), it.text) }, cues)
            for (minute in 5..90 step 5) if (CleanStreamAnchors.transformAt(shuffled, minute * 60_000L) != null) wrongOther++
            for (minute in 5..90 step 5) {
                val sceneInSubtitle = minute * 60_000L + 13_000L
                val sceneInData = toData(sceneInSubtitle)
                val tr = CleanStreamAnchors.transformAt(matches, sceneInData)
                if (tr == null) missing++ else if (abs(tr.map(sceneInData) - sceneInSubtitle) <= 1_500) found++ else { wrong++; System.err.println("WRONG seed=$seed min=$minute got=${tr.map(sceneInData)} want=$sceneInSubtitle") }
            }
        }
        assertEquals("scenes moved to a wrong place", 0, wrong)
        assertEquals("scenes moved by unrelated data", 0, wrongOther)
        assertEquals("scenes not placed", 0, missing)
    }
}
