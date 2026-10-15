package app.eye.android.ui.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.eye.android.R
import app.eye.android.core.BarcodeReader
import app.eye.android.core.vision.QrDecoder
import app.eye.android.ui.theme.EyeTheme
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Lancia la schermata di scansione e restituisce il contenuto grezzo del QR (o null). Il link non viene mai aperto. */
class ScanContract : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit) = Intent(context, ScanActivity::class.java)
    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.getStringExtra(ScanActivity.EXTRA_RAW) else null
}

class ScanActivity : ComponentActivity() {
    companion object { const val EXTRA_RAW = "raw" }

    private fun deliver(raw: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RAW, raw))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EyeTheme { Surface(Modifier.fillMaxSize()) { ScanScreen(::deliver) { finish() } } } }
    }
}

@Composable
private fun ScanScreen(onRaw: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var noQr by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val raw = runCatching { BarcodeReader.fromUri(ctx, uri) }.getOrNull()
            if (raw != null) onRaw(raw) else noQr = true
        }
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Column(Modifier.fillMaxSize()) {
        if (granted) {
            CameraPreview(Modifier.weight(1f).fillMaxWidth(), onRaw)
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(if (denied) R.string.scan_perm_denied else R.string.scan_perm_why), style = MaterialTheme.typography.bodyLarge)
                if (!denied) Button(
                    onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.scan_perm_allow)) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (noQr) Text(stringResource(R.string.scan_no_qr), color = MaterialTheme.colorScheme.error)
            if (granted) Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.scan_pick_image)) }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.scan_close)) }
        }
    }
}

@Composable
private fun CameraPreview(modifier: Modifier, onRaw: (String) -> Unit) {
    val owner = LocalLifecycleOwner.current
    var torchAvailable by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PreviewView(c).also { pv ->
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(executor) { proxy ->
                            if (done.get()) { proxy.close(); return@setAnalyzer }
                            val raw = try {
                                val plane = proxy.planes[0]
                                val buf = plane.buffer
                                val rs = plane.rowStride
                                val w = proxy.width
                                val h = proxy.height
                                val y = ByteArray(w * h)
                                for (r in 0 until h) { buf.position(r * rs); buf.get(y, r * w, w) }
                                QrDecoder.decodeLuma(y, w, h, proxy.imageInfo.rotationDegrees)
                            } catch (_: Exception) { null } finally { proxy.close() }
                            if (raw != null && done.compareAndSet(false, true)) onRaw(raw)
                        }
                        runCatching {
                            provider.unbindAll()
                            val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            camera = cam
                            torchAvailable = cam.cameraInfo.hasFlashUnit()
                        }
                    }, ContextCompat.getMainExecutor(c))
                }
            },
        )
        if (torchAvailable) OutlinedButton(
            onClick = { torchOn = !torchOn; camera?.cameraControl?.enableTorch(torchOn) },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) { Text(stringResource(if (torchOn) R.string.scan_torch_off else R.string.scan_torch_on)) }
    }
}
