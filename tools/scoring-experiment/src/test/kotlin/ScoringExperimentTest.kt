package dk.rodgrod.tools

import dk.rodgrod.core.audio.Synth
import dk.rodgrod.core.audio.Pcm
import dk.rodgrod.core.audio.Wav
import dk.rodgrod.core.scoring.Band
import dk.rodgrod.core.scoring.ScoreOutcome
import dk.rodgrod.core.scoring.ScoreRequest
import dk.rodgrod.core.scoring.Scorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScoringExperimentTest {
    @Test fun parsesFileNames() {
        assertEquals("mad" to "careful", parseName("mad_careful.wav"))
        assertEquals("rod_grod" to "anglicised", parseName("rod_grod_Anglicized.WAV"))
        assertNull(parseName("mad.wav"))
    }

    @Test fun wordListLoads() {
        val words = loadWordList(File("words.tsv"))
        assertTrue(words.size >= 15)
        assertEquals("rødgrød", words.getValue("rodgrod").danish)
    }

    @Test fun statsBehave() {
        assertEquals(1.0, Stats.auc(listOf(90.0, 80.0), listOf(10.0, 20.0)), 1e-9)
        assertEquals(0.5, Stats.auc(listOf(50.0), listOf(50.0)), 1e-9)
        assertEquals(80.0, Stats.bestThreshold(listOf(90.0, 80.0), listOf(10.0, 20.0)).first, 1e-9)
    }

    @Test fun accuracyVariantRescoresFromDetails() {
        val o = ScoreOutcome.Scored(90, Band.GOOD, true, "azure-pa", "mad", org.json.JSONObject().put("accuracy", 55.4))
        val v = accuracyVariant(o) as ScoreOutcome.Scored
        assertEquals(55, v.score); assertEquals(Band.RETRY, v.band); assertEquals(ACCURACY_VARIANT, v.scorer)
    }

    @Test fun endToEndWithFakeScorer() {
        val dir = Files.createTempDirectory("rec").toFile()
        val tone = Wav.encode(Pcm(Synth.tone(200.0, 400, 44100), 44100))
        val words = loadWordList(File("words.tsv"))
        val slugs = words.keys.take(6)
        for (s in slugs) {
            File(dir, "${s}_careful.wav").writeBytes(tone)
            File(dir, "${s}_anglicised.wav").writeBytes(tone)
        }
        File(dir, "notes.wav").writeBytes(tone) // ignored name
        var call = 0
        val fake = object : Scorer {
            override val name = "fake"
            override fun score(req: ScoreRequest): ScoreOutcome {
                assertTrue(req.wav16k.size > 44)
                val s = if (call++ % 2 == 0) 40 else 85 // files sort anglicised before careful
                return ScoreOutcome.Scored(s, if (s >= 80) Band.GOOD else Band.RETRY, true, "fake", "x")
            }
        }
        val rows = scoreFolder(dir, words, listOf(fake)) {}
        assertEquals(12, rows.size)
        val report = Report.build(rows, listOf("fake"), dk.rodgrod.core.scoring.Bands())
        assertTrue(report, report.contains("SEPARATES WELL"))
        val csv = toCsv(rows, listOf("fake"))
        assertEquals(13, csv.trim().lines().size)
    }
}
