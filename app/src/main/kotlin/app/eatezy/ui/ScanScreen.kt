package app.eatezy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.eatezy.ai.Product
import app.eatezy.data.fmt
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.Executors

private sealed interface ScanState {
    data object Scanning : ScanState
    data class Looking(val code: String) : ScanState
    data class Found(val product: Product) : ScanState
    data class NotFound(val code: String) : ScanState
}

/**
 * Barcode → Open Food Facts. A product is used only when its numbers add up; otherwise the
 * label is photographed and read by the AI.
 */
@Composable
fun ScanScreen(date: LocalDate) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    var state by remember { mutableStateOf<ScanState>(ScanState.Scanning) }
    var shotUri by rememberSaveable { mutableStateOf<String?>(null) }
    val labelCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = shotUri
        val code = (state as? ScanState.NotFound)?.code
        if (ok && u != null) {
            scope.launch { repo.logPhoto(date, Uri.parse(u), note = t("nutrition label", "этикетка с составом"), barcode = code) }
            nav.back()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(t("Barcode", "Штрихкод"), onBack = { nav.back() })
        Box(Modifier.weight(1f).fillMaxWidth().padding(S.s18).clip(RoundedCornerShape(S.radius)).background(C.surface)) {
            if (granted && state == ScanState.Scanning) {
                CameraScanner { code ->
                    if (state == ScanState.Scanning) {
                        state = ScanState.Looking(code)
                        scope.launch {
                            val p = repo.off.lookup(code)
                            state = if (p != null) ScanState.Found(p) else ScanState.NotFound(code)
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.8f).height(144.dp)
                        .border(2.dp, C.accent, RoundedCornerShape(S.s18)),
                )
            }
            if (!granted) Text(t("Camera access is needed", "Нужен доступ к камере"), style = T.body, modifier = Modifier.align(Alignment.Center))
            when (val s = state) {
                is ScanState.Looking -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = C.accent)
                    Spacer(Modifier.height(S.s12))
                    Text(s.code, style = T.small)
                }
                is ScanState.Found -> ProductCard(s.product, Modifier.align(Alignment.Center)) { grams ->
                    scope.launch { repo.logProduct(date, s.product, grams) }
                    nav.back()
                }
                is ScanState.NotFound -> Column(Modifier.align(Alignment.Center).padding(S.s24), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t("Not in the database or the data looks wrong", "Нет в базе или данные выглядят неверно"), style = T.h2)
                    Spacer(Modifier.height(S.s6))
                    Text(t("Take a photo of the nutrition label, the AI will read it.", "Сфотографируйте этикетку с КБЖУ — её прочитает ИИ."), style = T.small)
                    Spacer(Modifier.height(S.s18))
                    PrimaryButton(t("Photo of the label", "Фото этикетки"), Modifier.fillMaxWidth(), icon = Lucide.Camera) {
                        val u = newShotUri(context)
                        shotUri = u.toString()
                        labelCamera.launch(u)
                    }
                    Spacer(Modifier.height(S.s6))
                    GhostButton(t("Scan again", "Сканировать снова"), Modifier.fillMaxWidth()) { state = ScanState.Scanning }
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun ProductCard(p: Product, modifier: Modifier, onSave: (Double) -> Unit) {
    var grams by remember { mutableStateOf(p.suggestedGrams) }
    val f = grams / 100.0
    Column(modifier.fillMaxWidth().padding(S.s18)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (p.image != null) AsyncImage(p.image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(S.s72).clip(RoundedCornerShape(S.s12)))
            Spacer(Modifier.width(S.s12))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = T.h2)
                Text(t("per 100 g: ", "на 100 г: ") + "${p.per100.kcal.fmt(0)} ${unitLabel("kcal")}", style = T.small)
            }
        }
        Spacer(Modifier.height(S.s18))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Amount", "Количество"), style = T.body, modifier = Modifier.weight(1f))
            NumberField(grams, { grams = it }, Modifier.width(144.dp), digits = 0, suffix = unitLabel("g"))
        }
        Spacer(Modifier.height(S.s12))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${(p.per100.kcal * f).fmt(0)} ${unitLabel("kcal")}", style = T.h1)
            MacroPills(p.per100.protein * f, p.per100.carbs * f, p.per100.fat * f)
        }
        Spacer(Modifier.height(S.s18))
        PrimaryButton(t("Add", "Добавить"), Modifier.fillMaxWidth(), icon = Lucide.Check, enabled = grams > 0) { onSave(grams) }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraScanner(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
            ).build(),
        )
    }
    DisposableEffect(Unit) { onDispose { scanner.close(); executor.shutdown() } }
    AndroidView(
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null) { proxy.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes -> codes.firstOrNull()?.rawValue?.let { v -> ContextCompat.getMainExecutor(context).execute { onCode(v) } } }
                        .addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
        onRelease = { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } },
        modifier = Modifier.fillMaxSize(),
    )
}
