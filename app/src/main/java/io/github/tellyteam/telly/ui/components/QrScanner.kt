package io.github.tellyteam.telly.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.tellyteam.telly.ui.theme.Telly
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * The camera, reading QR codes: CameraX for the picture and ZXing to decode it, so it needs no
 * Google Play services and works on Fire tablets. The camera permission is asked for only when this
 * opens. Each code read goes to [onText] on the main thread (the same code again only once it's
 * been out of sight).
 */
@Composable
fun QrScanner(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; refused = !it }
    LaunchedEffect(Unit) { if (hasCamera && !granted) ask.launch(Manifest.permission.CAMERA) }

    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            !hasCamera -> Text("This device has no camera to scan with.", color = Telly.Muted)
            refused -> Text("telly needs the camera to scan the TV's QR code. You can allow it in Android's settings for telly.", color = Telly.Muted)
            granted -> CameraPreview(onText)
        }
    }
}

@Composable
private fun CameraPreview(onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onText)
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    LaunchedEffect(lifecycle) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val main = ContextCompat.getMainExecutor(context)
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor, QrAnalyzer { text -> main.execute { deliver(text) } }) }
        val picture = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, picture, analysis)
        }.recoverCatching {
            // A tablet with only a front camera.
            provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_FRONT_CAMERA, picture, analysis)
        }
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            provider.unbind(picture, analysis)
        }
    }
    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
}

/** Decodes each picture's brightness (the Y plane); a frame with no code is skipped. */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val decoder = QrDecoder()
    private var last: String? = null

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val text = decoder.decode(plane.buffer, it.width, it.height, plane.rowStride)
            if (text != last) {
                last = text
                if (text != null) onText(text)
            }
        }
    }
}

/** ZXing's QR reader over a camera's brightness plane, whose rows may be padded to [rowStride]. */
internal class QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

    fun decode(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int): String? {
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(data, y * width, width)
        }
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
