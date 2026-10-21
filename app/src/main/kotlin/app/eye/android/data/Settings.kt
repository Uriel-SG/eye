package app.eye.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import app.eye.android.core.update.UpdateSnooze
import app.eye.android.core.update.UpdateSnoozeStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.eye.android.core.ai.AiProvider
import app.eye.android.core.ai.AiUrls
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Aspetto: SYSTEM = segue il sistema (predefinito). */
enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED }

/** Quanto a lungo si conserva la cronologia ([days] 0 = sempre). Le analisi salvate non scadono mai. */
enum class HistoryRetention(val days: Int) {
    DAYS_7(7), DAYS_30(30), FOREVER(0);

    companion object {
        fun fromId(id: String?): HistoryRetention = entries.firstOrNull { it.name == id } ?: DAYS_7
    }
}

data class Settings(
    val onboardingSeen: Boolean? = null, // null = non ancora caricato
    val familyMode: Boolean = false,
    val saveHistory: Boolean = true,
    val aiProvider: AiProvider = AiProvider.ANTHROPIC,
    /** Vuoto = modello predefinito del provider. */
    val aiModel: String = "",
    val aiBaseUrl: String = "",
    /** Chiave API CIFRATA (Keystore); null = nessuna chiave. */
    val aiKeyCipher: String? = null,
    val aiConsent: Boolean = false,
    val aiSendUnredacted: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Protezione automatica voluta dall'utente (il permesso di sistema è un'altra cosa). */
    val autoProtect: Boolean = false,
    /** App di messaggistica che l'utente ha escluso. */
    val autoDisabledApps: Set<String> = emptySet(),
    /** Avvisi automatici sulle nuove campagne (opt-in, spenti di default). */
    val alertCampaigns: Boolean = false,
    val retention: HistoryRetention = HistoryRetention.DAYS_7,
    /** Controllo dell'eta' dei siti via RDAP (invia solo il nome del dominio al registro). Attivo di default. */
    val domainAgeCheck: Boolean = false,
    /** Chiede al servizio di accorciamento dove porta il link (mai il sito finale). */
    /** L'avviso prima di aprire VirusTotal non va piu' mostrato. */
    val vtNoticeDismissed: Boolean = false,
) {
    val aiHasKey: Boolean get() = !aiKeyCipher.isNullOrEmpty()

    /** Il secondo parere AI è offerto solo se c'è una chiave e la configurazione è completa. */
    val aiConfigured: Boolean
        get() = aiHasKey && (aiProvider != AiProvider.COMPATIBLE || (aiModel.isNotBlank() && AiUrls.validBase(aiBaseUrl) != null))
}

class SettingsStore(private val context: Context) {
    private val onboarding = booleanPreferencesKey("onboarding_seen")
    private val family = booleanPreferencesKey("family_mode")
    private val history = booleanPreferencesKey("save_history")

    private val aiProvider = stringPreferencesKey("ai_provider")
    private val aiModel = stringPreferencesKey("ai_model")
    private val aiBase = stringPreferencesKey("ai_base_url")
    private val aiKey = stringPreferencesKey("ai_key_ct")
    private val aiConsent = booleanPreferencesKey("ai_consent")
    private val theme = stringPreferencesKey("theme_mode")
    private val aiRaw = booleanPreferencesKey("ai_send_unredacted")
    private val autoKey = booleanPreferencesKey("auto_protect")
    private val autoOff = androidx.datastore.preferences.core.stringSetPreferencesKey("auto_disabled_apps")
    private val retentionKey = stringPreferencesKey("history_retention")
    private val alertKey = booleanPreferencesKey("alert_campaigns")
    private val ageKey = booleanPreferencesKey("domain_age_check_v2") // v2: dalla 1.6.1 disattivato di default (la vecchia chiave, predefinita ON, e' ignorata)
    private val vtKey = booleanPreferencesKey("vt_notice_dismissed")

    private val updCode = intPreferencesKey("update_snooze_code")
    private val updUntil = longPreferencesKey("update_snooze_until")

    /** «Più tardi» sull'avviso di aggiornamento dell'app (per versione disponibile). */
    val updateSnooze: UpdateSnoozeStore = object : UpdateSnoozeStore {
        override val snooze: Flow<UpdateSnooze> = context.dataStore.data.map { UpdateSnooze(it[updCode] ?: 0, it[updUntil] ?: 0L) }
        override suspend fun set(s: UpdateSnooze) = edit { it[updCode] = s.versionCode; it[updUntil] = s.untilMs }
    }

    val settings: Flow<Settings> = context.dataStore.data.map {
        Settings(
            it[onboarding] ?: false, it[family] ?: false, it[history] ?: true,
            AiProvider.fromId(it[aiProvider]), it[aiModel].orEmpty(), it[aiBase].orEmpty(), it[aiKey],
            it[aiConsent] ?: false, it[aiRaw] ?: false,
            ThemeMode.entries.firstOrNull { m -> m.name == it[theme] } ?: ThemeMode.SYSTEM,
            autoProtect = it[autoKey] ?: false, autoDisabledApps = it[autoOff] ?: emptySet(), alertCampaigns = it[alertKey] ?: false,
            retention = HistoryRetention.fromId(it[retentionKey]),
            domainAgeCheck = it[ageKey] ?: false, vtNoticeDismissed = it[vtKey] ?: false,
        )
    }

    suspend fun setOnboardingSeen() = edit { it[onboarding] = true }
    suspend fun setFamilyMode(v: Boolean) = edit { it[family] = v }
    suspend fun setSaveHistory(v: Boolean) = edit { it[history] = v }
    /** Provider, modello e base URL (il cambio di provider azzera il modello: ognuno ha il suo predefinito). */
    suspend fun setAiConfig(provider: AiProvider, model: String, baseUrl: String) = edit {
        it[aiProvider] = provider.id; it[aiModel] = model.trim(); it[aiBase] = baseUrl.trim()
    }
    suspend fun setAiKeyCipher(cipher: String?) = edit { if (cipher == null) it.remove(aiKey) else it[aiKey] = cipher }
    suspend fun setThemeMode(m: ThemeMode) = edit { it[theme] = m.name }
    suspend fun setAiConsent(v: Boolean) = edit { it[aiConsent] = v }
    suspend fun setAiSendUnredacted(v: Boolean) = edit { it[aiRaw] = v }
    suspend fun setAutoProtect(v: Boolean) = edit { it[autoKey] = v }
    suspend fun setAutoAppEnabled(pkg: String, enabled: Boolean) = edit {
        val cur = it[autoOff] ?: emptySet()
        it[autoOff] = if (enabled) cur - pkg else cur + pkg
    }
    suspend fun setRetention(r: HistoryRetention) = edit { it[retentionKey] = r.name }
    suspend fun setAlertCampaigns(v: Boolean) = edit { it[alertKey] = v }
    suspend fun setDomainAgeCheck(v: Boolean) = edit { it[ageKey] = v }
    suspend fun setVtNoticeDismissed(v: Boolean) = edit { it[vtKey] = v }
    suspend fun clearAll() = context.dataStore.edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
