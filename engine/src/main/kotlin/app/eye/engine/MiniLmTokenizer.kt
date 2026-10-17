package app.eye.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.BreakIterator
import java.util.Base64

/**
 * Tokenizer del modello MiniLM multilingue (SentencePiece Unigram stile XLM-R, vocabolario ridotto), reimplementato
 * in Kotlin puro a partire da `tokenizer.json` di HuggingFace: normalizzatore Precompiled (charsmap SentencePiece),
 * WhitespaceSplit + Metaspace("▁", prepend always, split), Unigram (Viterbi, unk fuso), template `<s> A </s>`,
 * troncamento a [maxLength] token. Parita' con il tokenizer Python verificata da MiniLmTokenizerTest.
 * Thread-safe dopo la costruzione.
 */
class MiniLmTokenizer(tokenizerJson: String, val maxLength: Int = 128) {
    private val pieces = HashMap<String, Int>()
    private val scores: DoubleArray
    private val unkId: Int
    private val unkScore: Double
    private var maxPieceCps = 1
    private val clsId: Int
    private val sepId: Int
    private val specials: List<Pair<String, Int>>
    private val maskId: Int
    private val trie: IntArray
    private val pool: ByteArray

    init {
        val root = Json.parseToJsonElement(tokenizerJson).jsonObject
        val model = root.getValue("model").jsonObject
        require(model.getValue("type").jsonPrimitive.content == "Unigram") { "tokenizer non Unigram" }
        val vocab: JsonArray = model.getValue("vocab").jsonArray
        scores = DoubleArray(vocab.size)
        var minScore = Double.MAX_VALUE
        for ((i, e) in vocab.withIndex()) {
            val a = e.jsonArray
            val piece = a[0].jsonPrimitive.content
            scores[i] = a[1].jsonPrimitive.double
            if (scores[i] < minScore) minScore = scores[i]
            pieces[piece] = i
            maxPieceCps = maxOf(maxPieceCps, piece.codePointCount(0, piece.length))
        }
        unkId = model.getValue("unk_id").jsonPrimitive.int
        unkScore = minScore - 10.0
        val added = root.getValue("added_tokens").jsonArray.map { it.jsonObject }
        specials = added.filter { it.getValue("special").jsonPrimitive.content == "true" }
            .map { it.getValue("content").jsonPrimitive.content to it.getValue("id").jsonPrimitive.int }
        clsId = specials.first { it.first == "<s>" }.second
        sepId = specials.first { it.first == "</s>" }.second
        maskId = specials.first { it.first == "<mask>" }.second
        val bytes = Base64.getDecoder().decode(
            root.getValue("normalizer").jsonObject.getValue("precompiled_charsmap").jsonPrimitive.content,
        )
        val trieBytes = le32(bytes, 0)
        trie = IntArray(trieBytes / 4) { le32(bytes, 4 + it * 4) }
        pool = bytes.copyOfRange(4 + trieBytes, bytes.size)
    }

    /** Token id con `<s>` ... `</s>`, al massimo [maxLength]. Il modello riceve attention_mask di soli 1. */
    fun encode(text: String): IntArray {
        val body = ArrayList<Int>()
        val limit = maxLength - 2
        val t = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        var pos = 0
        while (pos < t.length && body.size < limit) {
            // prossimo token speciale letterale (nel testo grezzo, come added_tokens non normalizzati)
            var at = -1
            var hit: Pair<String, Int>? = null
            for (sp in specials) {
                val i = t.indexOf(sp.first, pos)
                if (i >= 0 && (at < 0 || i < at)) { at = i; hit = sp }
            }
            val end = if (at < 0) t.length else at
            var seg = t.substring(pos, end)
            if (hit != null && hit.second == maskId) seg = seg.trimEnd { isWhite(it.code) } // lstrip del <mask>
            encodeSegment(seg, body, limit)
            if (hit == null) break
            if (body.size < limit) body.add(hit.second)
            pos = at + hit.first.length
        }
        val out = IntArray(minOf(body.size, limit) + 2)
        out[0] = clsId
        for (i in 0 until out.size - 2) out[i + 1] = body[i]
        out[out.size - 1] = sepId
        return out
    }

    private fun encodeSegment(seg: String, out: MutableList<Int>, limit: Int) {
        if (seg.isEmpty()) return
        val norm = normalize(seg)
        var i = 0
        while (i < norm.length && out.size < limit) {
            while (i < norm.length && isWhite(norm[i].code)) i++
            if (i >= norm.length) break
            val s = i
            while (i < norm.length && !isWhite(norm[i].code)) i++
            // Metaspace: "▁" + parola, spezzata prima di ogni "▁"
            val word = "▁" + norm.substring(s, i)
            var ps = 0
            for (k in 1..word.length) {
                if (k == word.length || word[k] == '▁') {
                    unigram(word.substring(ps, k), out, limit)
                    ps = k
                }
            }
        }
    }

    private fun unigram(piece: String, out: MutableList<Int>, limit: Int) {
        val cps = piece.codePoints().toArray()
        val n = cps.size
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val prevStart = IntArray(n + 1)
        val prevId = IntArray(n + 1)
        best[0] = 0.0
        for (start in 0 until n) {
            if (best[start] == Double.NEGATIVE_INFINITY) continue
            var single = false
            val sb = StringBuilder()
            for (len in 1..minOf(maxPieceCps, n - start)) {
                sb.appendCodePoint(cps[start + len - 1])
                val id = pieces[sb.toString()] ?: continue
                val local = best[start] + scores[id]
                val target = start + len
                if (local > best[target]) { best[target] = local; prevStart[target] = start; prevId[target] = id }
                if (len == 1) single = true
            }
            if (!single) {
                val local = best[start] + unkScore
                if (local > best[start + 1]) { best[start + 1] = local; prevStart[start + 1] = start; prevId[start + 1] = unkId }
            }
        }
        val rev = ArrayList<Int>()
        var e = n
        while (e > 0) { rev.add(prevId[e]); e = prevStart[e] }
        var last = -1
        for (k in rev.indices.reversed()) {
            val id = rev[k]
            if (id == unkId && last == unkId) continue // unk consecutivi fusi
            last = id
            if (out.size >= limit) return
            out.add(id)
        }
    }

    // --- normalizzatore Precompiled (SentencePiece charsmap, double-array Darts) ---

    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length + 8)
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(s)
        var st = bi.first()
        var en = bi.next()
        while (en != BreakIterator.DONE) {
            val g = s.substring(st, en)
            val gb = g.toByteArray(Charsets.UTF_8)
            val whole = if (gb.size < 6) transform(gb) else null
            if (whole != null) {
                sb.append(whole)
            } else {
                var i = 0
                while (i < g.length) {
                    val cp = g.codePointAt(i)
                    val n = Character.charCount(cp)
                    val part = g.substring(i, i + n)
                    sb.append(transform(part.toByteArray(Charsets.UTF_8)) ?: part)
                    i += n
                }
            }
            st = en
            en = bi.next()
        }
        return sb.toString()
    }

    /** Normalizzazione del prefisso di [key] con la corrispondenza piu' lunga trovata, o null. */
    private fun transform(key: ByteArray): String? {
        var nodePos = 0
        var unit = trie[nodePos]
        nodePos = nodePos xor offset(unit)
        var value = -1
        for (b in key) {
            val k = b.toInt() and 0xFF
            nodePos = nodePos xor k
            unit = trie[nodePos]
            if ((unit and (Int.MIN_VALUE or 0xFF)) != k) break
            nodePos = nodePos xor offset(unit)
            if ((unit shr 8) and 1 == 1) value = trie[nodePos] and Int.MAX_VALUE
        }
        if (value < 0) return null
        var end = value
        while (pool[end].toInt() != 0) end++
        return String(pool, value, end - value, Charsets.UTF_8)
    }

    private fun offset(unit: Int): Int = (unit ushr 10) shl ((unit and (1 shl 9)) shr 6)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    companion object {
        /** Oltre questa lunghezza il testo e' tagliato prima di tokenizzare (128 token sono sempre raggiunti prima). */
        const val MAX_CHARS = 4000

        /** White_Space Unicode (come char::is_whitespace di Rust, usato dal tokenizer di HuggingFace). */
        internal fun isWhite(c: Int): Boolean =
            c in 0x09..0x0D || c == 0x20 || c == 0x85 || c == 0xA0 || c == 0x1680 || c in 0x2000..0x200A ||
                c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000
    }
}
