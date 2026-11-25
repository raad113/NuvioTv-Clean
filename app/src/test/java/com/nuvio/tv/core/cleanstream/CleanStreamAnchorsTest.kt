package com.nuvio.tv.core.cleanstream

import com.nuvio.tv.core.cleanstream.SubtitleProfanity.Cue
import com.nuvio.tv.ui.screens.player.CleanStreamAnchors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Skip data names single swear words ("damn") at its own times; the English
 * subtitle was timed for another release. Realistic mess: some words in the
 * data are missing or softened in the subtitle, the subtitle has swears the
 * data missed, and common lookalikes ("hello", "shell") appear.
 */
class CleanStreamAnchorsTest {
    private val swearWords = listOf("fuck", "shit", "damn", "hell", "jesus", "christ", "bitch", "bastard")
    private val forms = mapOf("fuck" to listOf("fuck", "fucking", "Fuck!"), "shit" to listOf("shit", "Shit!", "bullshit", "shitty"),
        "damn" to listOf("damn", "goddamn", "Damn it"), "hell" to listOf("hell", "Hell!"), "jesus" to listOf("Jesus"),
        "christ" to listOf("Christ"), "bitch" to listOf("bitch", "son of a bitch"), "bastard" to listOf("bastard"))
    private val filler = listOf("We need to move.", "Where is she?", "I don't know.", "Come on!", "Hello there.", "Listen to me.",
        "He's coming back.", "Look out!", "Are you sure?", "Not yet.", "Keep going.", "Thank you.", "A shell on the beach.")

    @Test fun skipsFollowTheDialogue() {
        var found = 0; var missing = 0; var wrong = 0; var noise = 0
        for (seed in 1..20) {
            val rnd = Random(seed)
            val density = if (seed % 2 == 0) 0.03 else 0.08 // a mild film and a sweary one
            var t = 30_000L
            val cues = mutableListOf<Cue>()
            val anchors = mutableListOf<DialogueAnchor>()
            val scale = listOf(25.0 / 23.976, 1.0, 24.0 / 25.0)[seed % 3]
            val logo = rnd.nextLong(-60_000, 60_000)
            val toData = { x: Long -> ((x - logo) * scale).toLong() }
            repeat(1_200) {
                val s = t; val e = s + rnd.nextLong(1_000, 4_000)
                var text = filler[rnd.nextInt(filler.size)]
                if (rnd.nextDouble() < density) {
                    val w = swearWords[rnd.nextInt(swearWords.size)]
                    val shownInSubtitle = rnd.nextDouble() < 0.75 // else softened or left out
                    if (shownInSubtitle) text = text + " " + forms.getValue(w).random(rnd)
                    if (rnd.nextDouble() < 0.8) anchors += DialogueAnchor(toData(s + rnd.nextLong(0, e - s)), w)
                } else if (rnd.nextDouble() < 0.01) {
                    text += " " + forms.getValue(swearWords[rnd.nextInt(swearWords.size)]).random(rnd) // the data missed it
                }
                cues += Cue(s, e, text)
                t = e + rnd.nextLong(300, 8_000)
            }
            val matches = CleanStreamAnchors.match(anchors, cues)
            val shuffled = CleanStreamAnchors.match(anchors.map { DialogueAnchor(rnd.nextLong(0, 6_000_000), it.text) }, cues)
            for (minute in 5..90 step 5) {
                if (CleanStreamAnchors.transformAt(shuffled, minute * 60_000L) != null) noise++
                val sceneInSubtitle = minute * 60_000L + 13_000L
                val sceneInData = toData(sceneInSubtitle)
                val tr = CleanStreamAnchors.transformAt(matches, sceneInData)
                if (tr == null) missing++ else if (abs(tr.map(sceneInData) - sceneInSubtitle) <= 1_500) found++
                else { wrong++; System.err.println("WRONG seed=$seed min=$minute off=${tr.map(sceneInData) - sceneInSubtitle}") }
            }
        }
        assertEquals("scenes moved to a wrong place", 0, wrong)
        assertEquals("scenes moved by unrelated data", 0, noise)
        assertTrue("too many scenes not placed: $missing", missing <= 15)
    }
}
