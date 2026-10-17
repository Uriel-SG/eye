package app.eye.android.core.ai

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import app.eye.engine.TextClassifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Il modello gira in un PROCESSO SEPARATO (":ai"): un errore nativo in libonnxruntime (SIGSEGV/SIGILL, non
 * catturabile in Java) uccide solo quel processo; l'app continua con le sole regole. Il protocollo e' un Messenger:
 * MSG_SCORE (testo) -> MSG_RESULT (probabilita', NaN = non disponibile).
 */
class OnDeviceAiService : Service() {
    private var thread: HandlerThread? = null
    private var messenger: Messenger? = null
    private var classifier: OnnxTextClassifier? = null

    override fun onCreate() {
        super.onCreate()
        val c = OnnxTextClassifier(this).also { it.warmUp() }
        classifier = c
        val t = HandlerThread("eye-ai").apply { start() }
        thread = t
        messenger = Messenger(object : Handler(t.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != MSG_SCORE) return
                val p = try { c.scamProbability(msg.data.getString(KEY_TEXT).orEmpty()) } catch (_: Throwable) { null }
                val reply = Message.obtain(null, MSG_RESULT, msg.arg1, 0).apply {
                    data = Bundle().apply { putFloat(KEY_P, p ?: Float.NaN) }
                }
                try { msg.replyTo?.send(reply) } catch (_: Exception) { }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder
    override fun onDestroy() { thread?.quitSafely(); super.onDestroy() }

    companion object {
        const val MSG_SCORE = 1
        const val MSG_RESULT = 2
        const val KEY_TEXT = "t"
        const val KEY_P = "p"
    }
}

/** Client del processo ":ai". Qualsiasi problema (bind, timeout, crash del processo) = null = solo regole. */
class RemoteTextClassifier(context: Context, private val timeoutMillis: Long = 2500) : TextClassifier {
    private val ctx = context.applicationContext
    private val guard = ctx.getSharedPreferences("ai_guard", Context.MODE_PRIVATE)
    private val version = app.eye.android.BuildConfig.VERSION_CODE.toLong()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Float?>>()
    private val ids = AtomicInteger()
    private val replyThread = HandlerThread("eye-ai-reply").apply { start() }
    private val reply = Messenger(object : Handler(replyThread.looper) {
        override fun handleMessage(msg: Message) {
            if (msg.what != OnDeviceAiService.MSG_RESULT) return
            val p = msg.data.getFloat(OnDeviceAiService.KEY_P, Float.NaN)
            if (!p.isNaN()) markWorking()
            pending.remove(msg.arg1)?.complete(p.takeIf { !it.isNaN() })
        }
    })

    @Volatile private var connected = CompletableFuture<Messenger>()
    @Volatile private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { connected.complete(Messenger(service)) }
        override fun onServiceDisconnected(name: ComponentName?) = lost()
        override fun onBindingDied(name: ComponentName?) = lost()
        override fun onNullBinding(name: ComponentName?) { lost(); disable("binding nullo") }
    }

    private fun lost() {
        connected = CompletableFuture()
        val hadWork = pending.isNotEmpty()
        pending.values.forEach { it.complete(null) }
        pending.clear()
        // processo morto durante una richiesta o prima di aver mai funzionato: sospetto crash nativo
        if (hadWork || !guard.getBoolean(key("ok"), false)) recordCrash()
    }

    private fun key(k: String) = "$k.$version"
    private fun crashes() = guard.getInt(key("crashes"), 0)
    private fun disabled(): Boolean {
        val ok = guard.getBoolean(key("ok"), false)
        return crashes() >= if (ok) 3 else 1 // mai funzionato e gia' un crash: niente piu' tentativi su questa versione
    }
    private fun recordCrash() { guard.edit().putInt(key("crashes"), crashes() + 1).apply(); Log.w(TAG, "processo AI terminato (${crashes()})") }
    private fun markWorking() { if (!guard.getBoolean(key("ok"), false) || crashes() != 0) guard.edit().putBoolean(key("ok"), true).putInt(key("crashes"), 0).apply() }
    private fun disable(why: String) { guard.edit().putInt(key("crashes"), 99).apply(); Log.w(TAG, "AI disattivata: $why") }

    /** Avvia il processo e il caricamento del modello in background (non bloccante). */
    fun warmUp() {
        if (disabled()) return
        Thread({ runCatching { ensureBound() } }, "eye-ai-bind").apply { isDaemon = true }.start()
    }

    @Synchronized private fun ensureBound() {
        if (bound) return
        bound = try {
            ctx.bindService(Intent(ctx, OnDeviceAiService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) { false }
        if (!bound) disable("bindService fallito")
    }

    override fun scamProbability(text: String): Float? {
        if (disabled()) return null
        return try {
            ensureBound()
            if (!bound) return null
            val m = connected.get(timeoutMillis, TimeUnit.MILLISECONDS)
            val id = ids.incrementAndGet()
            val fut = CompletableFuture<Float?>()
            pending[id] = fut
            val msg = Message.obtain(null, OnDeviceAiService.MSG_SCORE, id, 0).apply {
                replyTo = reply
                data = Bundle().apply { putString(OnDeviceAiService.KEY_TEXT, text.take(4000)) }
            }
            try { m.send(msg) } catch (e: Exception) { pending.remove(id); return null }
            try { fut.get(timeoutMillis, TimeUnit.MILLISECONDS) } finally { pending.remove(id) }
        } catch (_: Throwable) {
            null
        }
    }

    private companion object { const val TAG = "EyeAI" }
}

/** true nel processo secondario ":ai" (li' non va costruito nulla dell'app). */
fun isAiProcess(app: Application): Boolean =
    runCatching {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else java.io.File("/proc/self/cmdline").readText().trim('\u0000')
        name.endsWith(":ai")
    }.getOrDefault(false)
