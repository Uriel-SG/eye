package app.eye.android.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import app.eye.android.R
import app.eye.android.core.OcrException
import app.eye.android.core.OcrReader
import app.eye.android.core.QrContent
import app.eye.android.core.ScanError
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.eye.android.AppContainer
import app.eye.android.core.CheckOutcome
import app.eye.android.core.CheckResult
import app.eye.android.core.CheckUseCase
import app.eye.android.core.Redactor
import app.eye.android.core.reasonTexts
import app.eye.android.core.ai.AiConfig
import app.eye.android.core.ai.AiError
import app.eye.android.core.ai.AiKeys
import app.eye.android.core.ai.AiOutcome
import app.eye.android.core.ai.AiProvider
import app.eye.android.core.ai.AiService
import app.eye.android.data.HistoryEntity
import app.eye.android.data.KeyVault
import app.eye.android.data.Settings
import app.eye.engine.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings> = c.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val history: StateFlow<List<HistoryEntity>> = c.history.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val useCase = CheckUseCase(c.analyzer, c.history, { c.settings.settings.first().saveHistory })
    private val controller = CheckController(viewModelScope, useCase, Dispatchers.Default)
    val state: StateFlow<CheckState> = controller.state

    fun onShared(text: String?) = controller.onShared(text)
    fun reset() = controller.reset()
    fun check(text: String, source: Source = Source.PASTE, alreadyTruncated: Boolean = false) = controller.check(text, source, alreadyTruncated)

    private val _qr = MutableStateFlow<QrContent?>(null)
    val qr: StateFlow<QrContent?> = _qr
    fun onQr(raw: String) { _qr.value = QrContent.parse(raw.take(CheckUseCase.MAX_CHARS + 1)) }
    fun clearQr() { _qr.value = null }

    /** OCR sul telefono; l'immagine non viene salvata e il testo letto passa dall'Analyzer con Source.OCR. */
    fun ocr(context: Context, uri: Uri) {
        controller.set(CheckState.Reading)
        val app = context.applicationContext
        viewModelScope.launch {
            val scan = try {
                withContext(Dispatchers.Default) { OcrReader.scanImage(app, uri) }
            } catch (e: OcrException) {
                controller.set(if (e.error == ScanError.Unavailable) CheckState.OcrUnavailable else CheckState.Error)
                return@launch
            } catch (e: Exception) {
                controller.set(CheckState.Error)
                return@launch
            }
            val qr = scan.qr
            when {
                // Solo QR: mostra il contenuto (Source.QR all'analisi), mai aperto.
                qr != null && scan.text.isBlank() -> { controller.reset(); onQr(qr) }
                // Testo e QR: analisi del testo combinato, con la presenza del QR indicata.
                qr != null -> check(scan.text + "\n\n[QR] " + qr, Source.OCR)
                scan.text.isBlank() -> controller.set(CheckState.NoText)
                else -> check(scan.text, Source.OCR)
            }
        }
    }

    // --- Secondo parere AI (facoltativo, mai automatico, mai salvato) ---
    private val aiService = AiService()
    private val _ai = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val ai: StateFlow<AiUiState> = _ai
    private val _aiTest = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val aiTest: StateFlow<AiUiState> = _aiTest

    fun clearAi() { _ai.value = AiUiState.Idle }
    fun clearAiTest() { _aiTest.value = AiUiState.Idle }

    /** Invia [text] al provider scelto con la chiave dell'utente; il risultato non tocca il verdetto delle regole. */
    fun askAi(text: String, consentNow: Boolean = false, unredactedNow: Boolean? = null) {
        val st = settings.value
        if (!st.aiConfigured || !(st.aiConsent || consentNow)) return
        val raw = unredactedNow ?: st.aiSendUnredacted
        _ai.value = AiUiState.Loading
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val key = st.aiKeyCipher?.let { KeyVault.decrypt(it) }
                if (key == null) AiOutcome.Failure(AiError.NOT_CONFIGURED)
                else aiService.ask(AiConfig(st.aiProvider, st.aiModel, st.aiBaseUrl, key), if (raw) text else Redactor.redactKeepLinks(text))
            }
            _ai.value = AiUiState.Done(outcome)
        }
    }

    private val _aiKeyError = MutableStateFlow(false)
    /** true se l'ultima chiave inserita non è stata accettata (caratteri non validi o errore di cifratura). */
    val aiKeyError: StateFlow<Boolean> = _aiKeyError
    fun clearAiKeyError() { _aiKeyError.value = false }

    /**
     * Salva provider/modello/URL e, se [newKey] non è vuota, la chiave (validata e cifrata).
     * Cambiare provider, indirizzo o chiave azzera il consenso all'invio (il destinatario è cambiato).
     */
    fun saveAi(provider: AiProvider, model: String, baseUrl: String, newKey: String) = viewModelScope.launch {
        val cur = settings.value
        val key = AiKeys.clean(newKey)
        if (key.isNotEmpty() && !AiKeys.isValid(key)) { _aiKeyError.value = true; return@launch }
        var ct: String? = null
        if (key.isNotEmpty()) {
            ct = withContext(Dispatchers.IO) { runCatching { KeyVault.encrypt(key) }.getOrNull() }
            if (ct == null) { _aiKeyError.value = true; return@launch } // la chiave già salvata non si tocca
        }
        _aiKeyError.value = false
        val destinationChanged = provider != cur.aiProvider || baseUrl.trim() != cur.aiBaseUrl || ct != null
        c.settings.setAiConfig(provider, model, baseUrl)
        if (ct != null) c.settings.setAiKeyCipher(ct)
        if (destinationChanged) { c.settings.setAiConsent(false); c.settings.setAiSendUnredacted(false) }
    }

    fun removeAiKey() = viewModelScope.launch {
        c.settings.setAiKeyCipher(null)
        c.settings.setAiConsent(false)
        c.settings.setAiSendUnredacted(false)
        withContext(Dispatchers.IO) { KeyVault.deleteKey() }
        clearAi()
    }

    fun setThemeMode(m: app.eye.android.data.ThemeMode) = viewModelScope.launch { c.settings.setThemeMode(m) }
    fun setAiConsent(v: Boolean) = viewModelScope.launch { c.settings.setAiConsent(v) }
    fun setAiSendUnredacted(v: Boolean) = viewModelScope.launch { c.settings.setAiSendUnredacted(v) }

    /** «Prova connessione» con i valori del modulo (chiave digitata, altrimenti quella salvata). */
    fun testAi(provider: AiProvider, model: String, baseUrl: String, typedKey: String) {
        _aiTest.value = AiUiState.Loading
        val saved = settings.value.aiKeyCipher
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val typed = AiKeys.clean(typedKey)
                if (typed.isNotEmpty() && !AiKeys.isValid(typed)) return@withContext AiOutcome.Failure(AiError.BAD_KEY)
                val key = typed.ifEmpty { saved?.let { KeyVault.decrypt(it) }.orEmpty() }
                AiService().ask(AiConfig(provider, model, baseUrl, key), AiService.TEST_TEXT)
            }
            _aiTest.value = AiUiState.Done(outcome)
        }
    }


    // --- Stato dei feed ---
    val rulesStaleDays: StateFlow<Long?> = c.rulesStaleDays
    val campaignStaleDays: StateFlow<Long?> = c.campaignStaleDays
    fun setRetention(r: app.eye.android.data.HistoryRetention) = viewModelScope.launch { c.settings.setRetention(r); c.history.purge() }

    // --- Cronologia: salvate ---
    fun setSaved(id: Long, saved: Boolean) = viewModelScope.launch { c.history.setSaved(id, saved) }

    /** Salva (segnalibro) un verdetto che non è in cronologia (es. cronologia disattivata); [onId] riceve l'id creato. */
    fun saveBookmark(result: CheckResult, onId: (Long) -> Unit) = viewModelScope.launch {
        val v = result.verdict
        val id = c.history.save(
            v.level.name, Redactor.preview(result.originalText), v.reasonTexts(), result.originalText,
            app.eye.android.data.VerdictJson.encode(v), saved = true,
        )
        onId(id)
    }


    // --- Catalogo: schede statiche + campagne del feed firmato ---
    val catalog: StateFlow<List<app.eye.android.core.ScamCard>> = c.campaigns
        .map { app.eye.android.core.Catalog.merge(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.eye.android.core.Catalog.cards)
    private val _catalogUpdate = MutableStateFlow(CatalogUpdate.IDLE)
    val catalogUpdate: StateFlow<CatalogUpdate> = _catalogUpdate
    private val _lastCatalogCheck = MutableStateFlow(c.feedState.lastCampaignCheck())
    /** Ultimo controllo riuscito (ms), 0 = mai. */
    val lastCatalogCheck: StateFlow<Long> = _lastCatalogCheck

    /** «Aggiorna». Non ricontrolla prima di un'ora dall'ultimo controllo riuscito. */
    fun refreshCatalog() {
        if (_catalogUpdate.value == CatalogUpdate.LOADING) return
        _catalogUpdate.value = CatalogUpdate.LOADING
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { c.feedUpdater.updateCampaigns(app.eye.android.core.feed.FeedUpdater.FREE_MIN_INTERVAL_MS) }
            _lastCatalogCheck.value = c.feedState.lastCampaignCheck()
            _catalogUpdate.value = when (r) {
                is app.eye.android.core.feed.FeedResult.Updated -> { c.reloadCampaigns(); CatalogUpdate.UPDATED }
                app.eye.android.core.feed.FeedResult.UpToDate -> CatalogUpdate.UP_TO_DATE
                is app.eye.android.core.feed.FeedResult.Failed ->
                    if (r.why == app.eye.android.core.feed.FeedFailure.NETWORK) CatalogUpdate.OFFLINE else CatalogUpdate.ERROR
            }
        }
    }

    /** L'utente ha aperto il catalogo: le campagne attuali contano come «già viste» (niente avviso per quelle). */
    fun markCatalogSeen() {
        c.feedState.setSeenCampaignIds(c.campaigns.value.map { it.id }.toSet())
    }

    private val _openCatalog = MutableStateFlow(false)
    val openCatalog: StateFlow<Boolean> = _openCatalog
    fun requestOpenCatalog() { _openCatalog.value = true }
    fun catalogOpened() { _openCatalog.value = false }

    fun setAlertCampaigns(v: Boolean) = viewModelScope.launch { c.settings.setAlertCampaigns(v) }
    // --- Protezione automatica ---
    private val _listenerGranted = MutableStateFlow(false)
    /** Accesso alle notifiche realmente concesso dal sistema (si rilegge a ogni rientro nella schermata). */
    val listenerGranted: StateFlow<Boolean> = _listenerGranted
    private val _alertsAllowed = MutableStateFlow(true)
    val alertsAllowed: StateFlow<Boolean> = _alertsAllowed

    fun refreshAutoAccess(ctx: Context) {
        _listenerGranted.value = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        _alertsAllowed.value = app.eye.android.core.notif.AlertNotifier.canPost(ctx)
    }
    fun setAutoProtect(v: Boolean) {
        // prima abilita/disabilita il servizio (serve già attivo quando si apre la pagina di sistema), poi salva la scelta
        app.eye.android.core.notif.ListenerControl.setEnabled(c.appContext, v)
        viewModelScope.launch { c.settings.setAutoProtect(v) }
    }
    fun setAutoApp(pkg: String, enabled: Boolean) = viewModelScope.launch { c.settings.setAutoAppEnabled(pkg, enabled) }
    fun purgeHistory() = viewModelScope.launch { c.history.purge() }
    fun deleteHistory(id: Long) = viewModelScope.launch { c.history.delete(id) }
    fun deleteAllHistory(includeSaved: Boolean = true) = viewModelScope.launch { c.history.deleteAll(includeSaved) }
    fun setOnboardingSeen() = viewModelScope.launch { c.settings.setOnboardingSeen() }
    fun setFamilyMode(v: Boolean) = viewModelScope.launch { c.settings.setFamilyMode(v) }
    fun setSaveHistory(v: Boolean) = viewModelScope.launch { c.settings.setSaveHistory(v) }
    fun clearAllData() = viewModelScope.launch {
        c.history.deleteAll()
        c.settings.clearAll()
        app.eye.android.core.notif.ListenerControl.setEnabled(c.appContext, false)
        withContext(Dispatchers.IO) { KeyVault.deleteKey(); app.eye.android.data.HistoryVault.deleteKey() }
        withContext(Dispatchers.IO) { app.eye.android.core.ShareCard.clearCache(c.appContext) } // F-05
        c.feedState.clear()
    }

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(c) as T
    }
}

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Loading : AiUiState
    data class Done(val outcome: AiOutcome) : AiUiState
}

/** Esito dell'ultimo «Aggiorna» del catalogo. */
enum class CatalogUpdate { IDLE, LOADING, UPDATED, UP_TO_DATE, OFFLINE, ERROR }
