package com.aethernova.nova

import com.aethernova.nova.ai.GeminiRetry
import com.aethernova.nova.ai.NoKeyException
import com.aethernova.nova.ai.QuotaException
import com.aethernova.nova.ai.TransientException
import com.aethernova.nova.data.LocalFood
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** v1.0.1: offline food matching must not inflate protein, and Gemini hiccups are retried. */
class V101Test {
    private fun names(s: String) = LocalFood.parse(s).associate { it.first.name to it.second }

    @Test fun realDayDoesNotBecomeSkyr() {
        val text = "had kappa puzhukku 300 g, 1 pack fish curry, banana, 7 almonds + 3 cashews, milk with bru and a flavour yogurt"
        val d = LocalFood.parseDetailed(text)
        val n = d.found.associate { it.first.name to it.second }
        assertFalse(n.keys.toString(), n.keys.any { it.startsWith("Skyr") })
        assertTrue(n.containsKey("Flavoured yogurt"))
        assertEquals(0.7, n.getValue("Almonds"), 1e-9)      // 7 nuts, not 7 portions
        assertEquals(0.3, n.getValue("Cashews"), 1e-9)
        val protein = d.found.sumOf { (i, q) -> i.protein * q }
        assertTrue("protein was $protein", protein in 45.0..60.0)
    }

    @Test fun plainYogurtIsNotSkyr() {
        assertTrue(names("had a yogurt").containsKey("Yogurt / curd (plain)"))
        assertTrue(names("had a skyr").keys.single().startsWith("Skyr"))
        assertTrue(names("greek yogurt pot").keys.single().startsWith("Skyr"))
    }

    @Test fun weightsScale() {
        assertEquals(1.2, names("ate 300 g kappa").getValue("Kappa (tapioca), cooked"), 1e-9)
        assertEquals(1.5, names("150g chicken").getValue("Chicken breast (cooked)"), 1e-9)
        assertEquals(1.2, names("300ml milk").getValue("Whole milk"), 1e-9)
        assertEquals(2.0, names("2 eggs").getValue("Egg"), 1e-9)
    }

    @Test fun unmatchedFoodIsReportedNotDropped() {
        val d = LocalFood.parseDetailed("had idiyappam and 2 eggs")
        assertEquals(listOf("Egg"), d.found.map { it.first.name })
        assertTrue(d.unmatched.toString(), d.unmatched.any { it.contains("idiyappam") })
        assertTrue(LocalFood.parseDetailed("had 2 eggs and a banana").unmatched.isEmpty())
    }

    @Test fun retryPolicy() = runBlocking {
        val calls = mutableListOf<String>()
        fun fake(vararg outcomes: Any?): suspend (String) -> String {
            var i = 0
            return { m -> calls += m; when (val o = outcomes[i++]) { is Exception -> throw o; else -> "ok:$m" } }
        }
        // 503 then fine on retry of the same model
        assertEquals("ok:lite", GeminiRetry.run("lite", "flash", sleep = {}, call = fake(TransientException("503"), null)))
        assertEquals(listOf("lite", "lite"), calls); calls.clear()
        // 503 twice -> other model
        assertEquals("ok:flash", GeminiRetry.run("lite", "flash", sleep = {}, call = fake(TransientException("503"), java.io.IOException("t"), null)))
        assertEquals(listOf("lite", "lite", "flash"), calls); calls.clear()
        // quota -> other model straight away, no waiting
        var slept = false
        assertEquals("ok:flash", GeminiRetry.run("lite", "flash", sleep = { slept = true }, call = fake(QuotaException("429"), null)))
        assertFalse(slept); calls.clear()
        // no key -> fail immediately (offline path takes over)
        try { GeminiRetry.run("lite", "flash", sleep = {}, call = fake(NoKeyException())); fail() } catch (e: NoKeyException) {}
        assertEquals(listOf("lite"), calls)
    }
}
