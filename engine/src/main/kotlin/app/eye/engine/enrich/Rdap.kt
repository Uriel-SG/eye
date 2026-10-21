package app.eye.engine.enrich

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime

/** Esito del parsing di una risposta RDAP per un dominio. */
sealed interface RdapParse {
    data class Registered(val atMs: Long) : RdapParse
    /** Risposta valida ma senza data di registrazione (es. DENIC): "eta' non disponibile". */
    data object NoRegistration : RdapParse
    /** Risposta non utilizzabile (errore RDAP, JSON rotto). */
    data object Invalid : RdapParse
}

object Rdap {
    const val MAX_BYTES = 256 * 1024

    /** Cerca events[eventAction=registration]; se piu' di uno, il piu' vecchio. Mai eccezioni. */
    fun parseRegistration(body: String): RdapParse {
        val obj = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return RdapParse.Invalid
        if (obj["errorCode"] != null) return RdapParse.Invalid
        val events = obj["events"] as? JsonArray ?: return RdapParse.NoRegistration
        val dates = events.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val action = (o["eventAction"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (!"registration".equals(action, ignoreCase = true)) return@mapNotNull null
            val date = (o["eventDate"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return@mapNotNull null
            runCatching { OffsetDateTime.parse(date).toInstant().toEpochMilli() }.getOrNull()
        }
        return dates.minOrNull()?.let { RdapParse.Registered(it) } ?: RdapParse.NoRegistration
    }
}

/** Bootstrap IANA (https://data.iana.org/rdap/dns.json): TLD -> server RDAP del registro. */
class RdapBootstrap private constructor(private val services: Map<String, List<String>>) {

    /** Base URL HTTPS (con '/' finale) del registro per [domain], col suffisso piu' lungo che combacia; null se senza RDAP. */
    fun serviceFor(domain: String): String? {
        if (!isValidDomain(domain)) return null
        val labels = domain.lowercase().split('.')
        for (i in labels.indices) {
            val urls = services[labels.subList(i, labels.size).joinToString(".")] ?: continue
            return pick(urls)
        }
        return null
    }

    /** URL della richiesta per [domain]: solo il nome del dominio viaggia. */
    fun queryUrl(domain: String): String? = serviceFor(domain)?.let { it + "domain/" + domain.lowercase() }

    private fun pick(urls: List<String>): String? {
        val https = urls.firstOrNull { it.startsWith("https://", ignoreCase = true) }
            ?: urls.firstOrNull { it.startsWith("http://", ignoreCase = true) }?.let { "https://" + it.substring(7) }
            ?: return null
        return if (https.endsWith("/")) https else "$https/"
    }

    companion object {
        private val domainRe = Regex("^(?=.{3,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])$")

        /** Solo nomi ASCII (punycode) a etichette valide: nient'altro finisce in un URL. */
        fun isValidDomain(d: String): Boolean = domainRe.matches(d.lowercase())

        fun parse(json: String): RdapBootstrap? {
            val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
            val list = root["services"] as? JsonArray ?: return null
            val map = HashMap<String, List<String>>()
            for (s in list) {
                val pair = s as? JsonArray ?: continue
                val tlds = (pair.getOrNull(0) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() } ?: continue
                val urls = (pair.getOrNull(1) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: continue
                if (urls.isNotEmpty()) tlds.forEach { map[it] = urls }
            }
            return if (map.isEmpty()) null else RdapBootstrap(map)
        }
    }
}
