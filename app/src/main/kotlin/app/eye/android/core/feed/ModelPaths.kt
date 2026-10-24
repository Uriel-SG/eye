package app.eye.android.core.feed

import android.content.Context
import app.eye.engine.BUNDLED_MODEL_VERSION
import app.eye.engine.FeedIndex
import app.eye.engine.modelStore
import java.io.File

/** Posizione del modello AI del feed (filesDir, esclusa dai backup) e risoluzione del modello attivo. Usata da entrambi i processi. */
object ModelPaths {
    fun store(ctx: Context) = modelStore(File(ctx.applicationContext.filesDir, "feeds"))
    fun modelDir(ctx: Context) = File(ctx.applicationContext.filesDir, "models")

    @Volatile private var tokenizerSha: String? = null

    /** sha256 del tokenizer.json incluso nell'app: il feed puo' sostituire il modello solo se dichiara questo. */
    fun bundledTokenizerSha(ctx: Context): String = tokenizerSha ?: synchronized(this) {
        tokenizerSha ?: FeedIndex.sha256Hex(ctx.applicationContext.assets.open("ml/tokenizer.json").use { it.readBytes() }).also { tokenizerSha = it }
    }

    /** Modello del feed da usare adesso (riverificato), o null = modello dell'APK. */
    fun installed(ctx: Context): InstalledModel? =
        InstalledModel.resolve(store(ctx), modelDir(ctx), bundledTokenizerSha(ctx))

    /** Versione del modello in uso secondo i file su disco (senza caricarlo): quella del feed o quella dell'APK. */
    fun activeVersion(ctx: Context): Long = installed(ctx)?.manifest?.version ?: BUNDLED_MODEL_VERSION
}
