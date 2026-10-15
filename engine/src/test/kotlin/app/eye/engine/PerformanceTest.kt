package app.eye.engine

import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class PerformanceTest {
    private val engine = RuleEngine.default()

    private fun timeMs(text: String): Double {
        val t0 = System.nanoTime()
        engine.analyze(Input(text, Source.PASTE))
        return (System.nanoTime() - t0) / 1e6
    }

    @Test fun p95UnderBudgetOnTypicalMessages() {
        val samples = ArrayList<String>()
        File("../corpus").listFiles { f -> f.extension == "jsonl" }?.forEach { f ->
            f.readLines().filter { it.isNotBlank() }.forEach {
                samples.add(Regex("\"text\": ?\"((?:[^\"\\\\]|\\\\.)*)\"").find(it)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: it)
            }
        }
        if (samples.isEmpty()) samples.add("Poste: il tuo pacco e' in giacenza, paga 1,99 EUR su https://poste-consegna.com/x")
        repeat(300) { samples.forEach { engine.analyze(Input(it, Source.PASTE)) } } // warm-up JIT
        val times = samples.flatMap { s -> List(5) { timeMs(s) } }.sorted()
        val p95 = times[(times.size * 95 / 100).coerceAtMost(times.size - 1)]
        println("PERF messaggi tipici: n=${times.size} p50=${"%.3f".format(times[times.size / 2])}ms p95=${"%.3f".format(p95)}ms max=${"%.3f".format(times.last())}ms")
        assertTrue(p95 < 1000, "p95 $p95 ms")
        assertTrue(p95 < 20, "obiettivo interno p95 < 20 ms, misurato $p95 ms")
    }

    private fun hostile(): Map<String, String> {
        val n = MAX_INPUT_CHARS
        val rnd = Random(42)
        return mapOf(
            "a ripetuto" to "a".repeat(n),
            "spazi" to " ".repeat(n),
            "a! a! a!" to "a!".repeat(n / 2),
            "punti" to ".".repeat(n),
            "cifre" to "0".repeat(n),
            "cifre e spazi" to "0 ".repeat(n / 2),
            "pacco consegna" to "pacco ".repeat(n / 6),
            "pacco + spazi e keyword" to ("pacco " + "x ".repeat(n / 4) + "giacenza").take(n),
            "iban-like" to "IT60 ".repeat(n / 5),
            "iban-like 2" to "IT60X".repeat(n / 5),
            "url lunghi" to "http://" + "a.".repeat(n / 2),
            "url senza spazi" to "www." + "a".repeat(n - 8) + ".com",
            "etichette" to "a".repeat(60) + "." + "b".repeat(60) + "." + "c".repeat(n - 130),
            "chiocciole" to "@".repeat(n),
            "parentesi" to "(".repeat(n / 2) + ")".repeat(n / 2),
            "quasi-regola OTP" to ("comunica " + "a ".repeat(n / 4) + " pin").take(n),
            "quasi-regola nested" to "account ".repeat(n / 8),
            "unicode misto" to "аpple р0ste ".repeat(n / 12),
            "zero-width" to "p​o​s​t​e ".repeat(n / 10),
            "casuale" to String(CharArray(n) { (rnd.nextInt(0x2FF)).toChar() }),
            "casuale ascii" to String(CharArray(n) { (32 + rnd.nextInt(95)).toChar() }),
            "oltre il limite" to "x".repeat(n * 50),
            "emoji" to "👉".repeat(n / 2),
        )
    }

    @Test fun redosHostileInputsFinishQuickly() {
        engine.analyze(Input("warm up", Source.PASTE))
        for ((name, text) in hostile()) {
            val ms = timeMs(text)
            println("REDOS %-24s %.1f ms".format(name, ms))
            assertTrue(ms < 1000, "input ostile '$name' ha richiesto $ms ms")
        }
    }

    @Test fun hostileSenderIsBounded() {
        val t0 = System.nanoTime()
        engine.analyze(Input("ciao", Source.SHARE, "x".repeat(1_000_000)))
        assertTrue((System.nanoTime() - t0) / 1e6 < 1000)
    }
}
