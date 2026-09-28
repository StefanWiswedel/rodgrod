package dk.rodgrod.core.scoring

import dk.rodgrod.core.azure.AzureCredentials
import dk.rodgrod.core.azure.AzureStt
import dk.rodgrod.core.azure.HttpClient
import dk.rodgrod.core.azure.HttpResponse
import dk.rodgrod.core.azure.Recognition
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class BandsTest {
    @Test fun defaultBands() {
        val b = Bands()
        assertEquals(Band.GOOD, b.of(100)); assertEquals(Band.GOOD, b.of(80))
        assertEquals(Band.CLOSE, b.of(79)); assertEquals(Band.CLOSE, b.of(60))
        assertEquals(Band.RETRY, b.of(59)); assertEquals(Band.RETRY, b.of(0))
    }

    @Test fun customBands() {
        val b = Bands(goodMin = 90, closeMin = 50)
        assertEquals(Band.CLOSE, b.of(85)); assertEquals(Band.RETRY, b.of(49))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsInvertedBands() { Bands(goodMin = 50, closeMin = 70) }
}

class TextNormTest {
    @Test fun normalizes() {
        assertEquals("hvad koster det", TextNorm.normalize("Hvad koster det?"))
        assertEquals("rødgrød med fløde", TextNorm.normalize("  Rødgrød  med fløde! "))
    }

    @Test fun editDistance() {
        assertEquals(0, TextNorm.levenshtein("mad", "mad"))
        assertEquals(1, TextNorm.levenshtein("mad", "mat"))
        assertEquals(3, TextNorm.levenshtein("", "abc"))
        assertEquals(1.0, TextNorm.similarity("Tak!", "tak"), 1e-9)
        assertEquals(2.0 / 3, TextNorm.similarity("mad", "mat"), 1e-9)
    }
}

private const val PA_RESPONSE = """{"RecognitionStatus":"Success","Offset":700000,"Duration":8400000,"DisplayText":"Rød grød.","SNR":38.7,
 "NBest":[{"Confidence":0.98,"Lexical":"rød grød","ITN":"rød grød","MaskedITN":"rød grød","Display":"Rød grød.",
 "AccuracyScore":72.0,"FluencyScore":90.0,"CompletenessScore":100.0,"PronScore":78.4,
 "Words":[{"Word":"rød","AccuracyScore":60.0,"ErrorType":"Mispronunciation"},{"Word":"grød","AccuracyScore":84.0,"ErrorType":"None"}]}]}"""

private const val PA_RESPONSE_NESTED = """{"RecognitionStatus":"Success","NBest":[{"Confidence":0.9,"Lexical":"tak",
 "PronunciationAssessment":{"AccuracyScore":91.0,"FluencyScore":100.0,"CompletenessScore":100.0,"PronScore":93.0},
 "Words":[{"Word":"tak","PronunciationAssessment":{"AccuracyScore":91.0,"ErrorType":"None"}}]}]}"""

class PronunciationAssessmentTest {
    @Test fun parsesFlatResponse() {
        val r = PronunciationAssessmentScorer.interpret(Recognition("Success", JSONObject(PA_RESPONSE)), Bands())
        assertEquals(78, r.score)
        assertEquals(Band.CLOSE, r.band)
        assertTrue(r.reliable)
        assertEquals("rød grød", r.recognized)
        assertEquals(2, r.details.getJSONArray("words").length())
    }

    @Test fun accuracyMetricUsesAccuracyScore() {
        val r = PronunciationAssessmentScorer.interpret(Recognition("Success", JSONObject(PA_RESPONSE)), Bands(),
            metric = PronunciationAssessmentScorer.Metric.ACCURACY)
        assertEquals(72, r.score)
        assertEquals(Band.CLOSE, r.band)
    }

    @Test fun parsesNestedResponse() {
        val r = PronunciationAssessmentScorer.interpret(Recognition("Success", JSONObject(PA_RESPONSE_NESTED)), Bands())
        assertEquals(93, r.score); assertEquals(Band.GOOD, r.band)
    }

    @Test fun noMatchIsUnreliableRetry() {
        val r = PronunciationAssessmentScorer.interpret(Recognition("NoMatch", JSONObject("""{"RecognitionStatus":"NoMatch"}""")), Bands())
        assertEquals(0, r.score); assertEquals(Band.RETRY, r.band); assertFalse(r.reliable)
    }

    @Test fun allOmittedIsUnreliable() {
        val json = """{"RecognitionStatus":"Success","NBest":[{"Lexical":"","AccuracyScore":0,"CompletenessScore":0,"PronScore":0,
            "Words":[{"Word":"tak","AccuracyScore":0,"ErrorType":"Omission"}]}]}"""
        val r = PronunciationAssessmentScorer.interpret(Recognition("Success", JSONObject(json)), Bands())
        assertFalse(r.reliable)
    }

    @Test fun sendsAssessmentHeaderAndMapsErrors() {
        val http = FakeHttp { url, headers, _ ->
            assertTrue(url.contains("language=da-DK"))
            val pa = JSONObject(String(Base64.getDecoder().decode(headers.getValue("Pronunciation-Assessment"))))
            assertEquals("mad", pa.getString("ReferenceText"))
            assertEquals("HundredMark", pa.getString("GradingSystem"))
            HttpResponse(200, PA_RESPONSE.toByteArray())
        }
        val scorer = PronunciationAssessmentScorer(AzureStt(AzureCredentials("k", "westeurope"), http), Bands())
        val out = scorer.score(ScoreRequest("mad", ByteArray(10)))
        assertTrue(out is ScoreOutcome.Scored)

        val offline = PronunciationAssessmentScorer(AzureStt(AzureCredentials("k", "westeurope"), FakeHttp { _, _, _ -> throw IOException("no network") }), Bands())
        val u = offline.score(ScoreRequest("mad", ByteArray(10)))
        assertTrue(u is ScoreOutcome.Unavailable)
        assertFalse((u as ScoreOutcome.Unavailable).configProblem)

        val badKey = PronunciationAssessmentScorer(AzureStt(AzureCredentials("k", "westeurope"), FakeHttp { _, _, _ -> HttpResponse(401, ByteArray(0)) }), Bands())
        val b = badKey.score(ScoreRequest("mad", ByteArray(10)))
        assertTrue((b as ScoreOutcome.Unavailable).configProblem)
    }
}

class FallbackScorerTest {
    private fun rec(json: String) = JSONObject(json).let { Recognition(it.optString("RecognitionStatus"), it) }

    @Test fun exactConfidentMatchScoresHigh() {
        val da = rec("""{"RecognitionStatus":"Success","NBest":[{"Confidence":0.95,"Lexical":"tak"}]}""")
        val r = AsrFallbackScorer.combine("Tak!", da, null, Bands())
        assertEquals(98, r.score); assertEquals(Band.GOOD, r.band); assertTrue(r.reliable)
    }

    @Test fun picksBestNBestAndKeepsAnglicisedSignalOutOfScore() {
        val da = rec("""{"RecognitionStatus":"Success","NBest":[{"Confidence":0.5,"Lexical":"mat"},{"Confidence":0.4,"Lexical":"mad"}]}""")
        val en = rec("""{"RecognitionStatus":"Success","NBest":[{"Confidence":0.9,"Lexical":"mad"}]}""")
        val withEn = AsrFallbackScorer.combine("mad", da, en, Bands())
        val withoutEn = AsrFallbackScorer.combine("mad", da, null, Bands())
        assertEquals(withoutEn.score, withEn.score)
        assertEquals("mad", withEn.recognized)
        assertEquals(0.5, withEn.anglicised!!.likelihood, 1e-9)
    }

    @Test fun noMatchScoresZero() {
        val r = AsrFallbackScorer.combine("mad", rec("""{"RecognitionStatus":"NoMatch"}"""), null, Bands())
        assertEquals(0, r.score); assertFalse(r.reliable); assertNull(r.recognized)
    }
}

class HvptAnswerTest {
    @Test fun parsesAnswers() {
        assertEquals(0, HvptAnswerRecognizer.parse("One."))
        assertEquals(1, HvptAnswerRecognizer.parse("two"))
        assertEquals(1, HvptAnswerRecognizer.parse("Too"))
        assertEquals(0, HvptAnswerRecognizer.parse("number one"))
        assertNull(HvptAnswerRecognizer.parse("one or two"))
        assertNull(HvptAnswerRecognizer.parse("banana"))
    }
}

class FakeHttp(val handler: (String, Map<String, String>, ByteArray?) -> HttpResponse) : HttpClient {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int) = handler(url, headers, body)
}
