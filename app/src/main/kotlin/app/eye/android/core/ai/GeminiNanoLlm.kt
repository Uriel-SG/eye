package app.eye.android.core.ai

import app.eye.engine.Input
import app.eye.engine.Verdict
import app.eye.engine.llm.LlmOpinion
import app.eye.engine.llm.LlmOpinionParser
import app.eye.engine.llm.LlmPrompt
import app.eye.engine.llm.LlmStatus
import app.eye.engine.llm.OnDeviceLlm
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gemini Nano via ML Kit GenAI Prompt API (AICore). Solo con l'app in primo piano: in background il sistema rifiuta
 * (BACKGROUND_USE_BLOCKED), quindi qui non si chiama mai dal servizio notifiche. Tutto resta sul telefono e non si
 * registra alcun contenuto. Ogni errore diventa UNAVAILABLE / null: nessuna eccezione verso la UI.
 */
class GeminiNanoLlm(private val timeoutMs: Long = GENERATION_TIMEOUT_MS) : OnDeviceLlm {
    private val model: GenerativeModel? by lazy { runCatching { Generation.getClient() }.getOrNull() }

    override suspend fun status(): LlmStatus {
        val m = model ?: return LlmStatus.UNAVAILABLE
        return try {
            when (m.checkStatus()) {
                FeatureStatus.AVAILABLE -> LlmStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> LlmStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> LlmStatus.DOWNLOADING
                else -> LlmStatus.UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            LlmStatus.UNAVAILABLE
        }
    }

    override suspend fun download(): Boolean {
        val m = model ?: return false
        return try {
            m.download().first { it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed } is DownloadStatus.DownloadCompleted
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun opinion(input: Input, verdict: Verdict): LlmOpinion? {
        val m = model ?: return null
        return try {
            val raw = withTimeoutOrNull(timeoutMs) {
                val req = generateContentRequest(TextPart(LlmPrompt.build(input, verdict))) {
                    temperature = 0.1f
                    candidateCount = 1
                    maxOutputTokens = 300
                }
                m.generateContent(req).candidates.firstOrNull()?.text
            }
            LlmOpinionParser.parse(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null // BACKGROUND_USE_BLOCKED, quota, modello non pronto, ecc.: parere non disponibile
        }
    }

    companion object {
        const val GENERATION_TIMEOUT_MS = 15_000L
    }
}
