package app.eye.android.data

import app.eye.android.core.MiniJson
import app.eye.android.core.age.DomainAgeResult
import app.eye.engine.enrich.DomainAgeEntry

/** Eta' dei domini salvata con la voce di cronologia (solo nomi di dominio e date; cifrata su disco). */
object DomainAgeJson {
    fun encode(r: DomainAgeResult): String = MiniJson.stringify(
        mapOf("t" to r.checkedAt, "d" to r.entries.map { mapOf("n" to it.domain, "r" to it.registeredAtMs) }),
    )

    /** null se assente o corrotto. */
    fun decode(json: String): DomainAgeResult? {
        if (json.isEmpty()) return null
        val m = MiniJson.parse(json) as? Map<*, *> ?: return null
        val at = (m["t"] as? Number)?.toLong() ?: return null
        val list = (m["d"] as? List<*>)?.mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            DomainAgeEntry(o["n"] as? String ?: return@mapNotNull null, (o["r"] as? Number)?.toLong())
        } ?: return null
        return DomainAgeResult(list, at, incomplete = false)
    }
}
