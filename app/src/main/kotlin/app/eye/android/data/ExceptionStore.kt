package app.eye.android.data

import android.content.Context
import app.eye.android.core.MiniJson
import app.eye.engine.ExceptionKind
import app.eye.engine.UserException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Eccezioni dell'utente: un solo blob JSON cifrato (Keystore) su disco, escluso dai backup. Nessun log di contenuti. */
object ExceptionsVault : KeystoreCrypto("eye_exceptions_v1")

class ExceptionStore(
    private val read: () -> String?,
    private val write: (String?) -> Unit,
    private val crypto: TextCrypto,
) {
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<UserException>> = _items

    fun snapshot(): List<UserException> = _items.value

    @Synchronized
    fun add(e: UserException): Boolean {
        val list = (listOf(e) + _items.value).take(MAX)
        return persist(list)
    }

    @Synchronized
    fun remove(id: String) { persist(_items.value.filter { it.id != id }) }

    @Synchronized
    fun clear() { write(null); _items.value = emptyList() }

    private fun persist(list: List<UserException>): Boolean {
        val enc = runCatching { crypto.encrypt(MiniJson.stringify(list.map(::toMap))) }.getOrNull() ?: return false
        write(enc)
        _items.value = list
        return true
    }

    private fun load(): List<UserException> {
        val enc = read() ?: return emptyList()
        val plain = crypto.decrypt(enc) ?: return emptyList()
        return (MiniJson.parse(plain) as? List<*>)?.mapNotNull { fromMap(it as? Map<*, *>) }.orEmpty()
    }

    private fun toMap(e: UserException): Map<String, Any?> = mapOf(
        "id" to e.id, "k" to e.kind.name, "o" to e.origin, "s" to e.sender, "sh" to e.shingles.toList(),
        "d" to e.domains.toList(), "p" to e.preview, "t" to e.createdAt, "r" to e.ruleIds.toList(),
    )

    private fun fromMap(m: Map<*, *>?): UserException? {
        m ?: return null
        fun strs(k: String) = (m[k] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        return UserException(
            id = m["id"] as? String ?: return null,
            kind = runCatching { ExceptionKind.valueOf(m["k"] as String) }.getOrNull() ?: return null,
            origin = m["o"] as? String, sender = m["s"] as? String,
            shingles = strs("sh"), domains = strs("d"), preview = m["p"] as? String ?: "",
            createdAt = (m["t"] as? Number)?.toLong() ?: 0L, ruleIds = strs("r"),
        )
    }

    companion object {
        const val MAX = 200
        fun create(ctx: Context): ExceptionStore {
            val prefs = ctx.getSharedPreferences("user_exceptions", Context.MODE_PRIVATE)
            return ExceptionStore(
                { prefs.getString("v1", null) },
                { v -> prefs.edit().apply { if (v == null) remove("v1") else putString("v1", v) }.apply() },
                ExceptionsVault,
            )
        }
    }
}
