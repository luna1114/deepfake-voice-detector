package com.example.mainactivity.ui.detect

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mainactivity.R
import com.example.mainactivity.network.ApiClient
import com.example.mainactivity.network.PredictionResponse
import com.example.mainactivity.notify.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

@Composable
fun DetectionScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val conf = LocalConfiguration.current
    val isLandscape = conf.screenWidthDp > conf.screenHeightDp

    // ---- 파일 선택/녹음 상태 ----
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }

    val recorder = remember { AudioRecorder() }
    var isRecording by remember { mutableStateOf(false) }
    var recordedFile by remember { mutableStateOf<File?>(null) }

    // 2단계 확인을 위한 “마지막 업로드 파일/응답” 보관
    var lastUploadFile by remember { mutableStateOf<File?>(null) }
    var lastServerPhone by remember { mutableStateOf<String?>(null) }

    // ---- 미리듣기 ----
    val player = remember { MediaPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0) }

    // ---- 서버 결과/로딩 ----
    var isDetecting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DetectionResult?>(null) }

    // ---- 내부 팝업 (확인형) ----
    var showConfirm by remember { mutableStateOf(false) }
    var confirmTitle by remember { mutableStateOf("") }
    var confirmBody by remember { mutableStateOf("") }

    // 파일 선택
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        pickedUri = uri
        pickedName = guessDisplayName(ctx.contentResolver, uri)
        recordedFile = null
        result = null
        lastUploadFile = null
        lastServerPhone = null
    }

    // 마이크 권한 → 녹음 시작
    val micPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                scope.launch { startRecord(ctx, recorder) { isRecording = it } }
            }
        }

    // MediaPlayer 정리
    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    // 재생 위치 폴링
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(0)
            durationMs = runCatching { player.duration }.getOrDefault(0)
            delay(200)
        }
    }

    // =============== UI ===============
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF9333EA), Color(0xFF2563EB))))
            .padding(if (isLandscape) 24.dp else 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = if (isLandscape) 1100.dp else 896.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 헤더
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_left),
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Column {
                    Text("오디오 판별",
                        fontSize = 36.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "파일 업로드 또는 녹음 후 『지금 감지하세요』",
                        fontSize = 18.sp, color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            // 카드
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(8.dp),
                colors = CardDefaults.cardColors(Color.White)
            ) {
                Column(
                    Modifier.padding(if (isLandscape) 32.dp else 48.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // 업로드 아이콘
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFE9D5FF), Color(0xFFBFDBFE))
                                )
                            )
                            .align(Alignment.CenterHorizontally),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_upload),
                            contentDescription = null,
                            tint = Color(0xFF9333EA),
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    // 선택 파일명
                    AnimatedVisibility(
                        visible = pickedName != null || recordedFile != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Text(
                            text = pickedName ?: recordedFile?.name ?: "",
                            fontSize = 16.sp, color = Color(0xFF64748B),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    // 파일 선택
                    OutlinedButton(
                        onClick = { pickLauncher.launch("audio/*") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(2.dp, Color(0xFFE5E7EB)),
                        colors = ButtonDefaults.outlinedButtonColors(Color.White)
                    ) { Text("오디오 파일을 선택하세요", fontSize = 16.sp, color = Color(0xFF374151)) }

                    // 녹음/미리듣기
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 녹음
                        OutlinedButton(
                            onClick = {
                                if (isRecording) {
                                    // ► 녹음 종료 & 파일 확보
                                    scope.launch {
                                        val wav = stopRecord(recorder)
                                        isRecording = false
                                        if (wav != null && wav.exists()) {
                                            recordedFile = wav
                                            pickedUri = null
                                            pickedName = wav.name
                                            result = null
                                            lastUploadFile = null
                                            lastServerPhone = null
                                        }
                                    }
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(
                                2.dp, if (isRecording) Color(0xFFFCA5A5) else Color(0xFFE5E7EB)
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isRecording) Color(0xFFFEE2E2) else Color.White
                            )
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_mic),
                                contentDescription = null,
                                tint = if (isRecording) Color(0xFFDC2626) else Color(0xFF374151)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isRecording) "녹음 중지" else "녹음하기",
                                color = if (isRecording) Color(0xFFDC2626) else Color(0xFF374151)
                            )
                        }

                        // 미리듣기
                        OutlinedButton(
                            onClick = {
                                // 녹음 파일 우선, 없으면 선택 파일을 캐시에 복사
                                val src: File? = when {
                                    recordedFile != null && recordedFile!!.exists() -> recordedFile
                                    pickedUri != null -> copyUriToCache(ctx, pickedUri!!)
                                    else -> null
                                }

                                if (src != null && src.exists()) {
                                    if (!isPlaying) {
                                        runCatching {
                                            player.reset()
                                            player.setDataSource(src.absolutePath)
                                            player.setOnPreparedListener {
                                                durationMs = it.duration
                                                it.start()
                                                isPlaying = true
                                            }
                                            player.setOnCompletionListener {
                                                isPlaying = false
                                                positionMs = 0
                                            }
                                            player.prepareAsync()
                                        }
                                    } else {
                                        runCatching { player.pause() }
                                        isPlaying = false
                                    }
                                }
                            },
                            enabled = (pickedUri != null || recordedFile != null),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(2.dp, Color(0xFFE5E7EB)),
                            colors = ButtonDefaults.outlinedButtonColors(Color.White)
                        ) {
                            Icon(
                                painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                                contentDescription = null,
                                tint = Color(0xFF374151)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("미리 듣기", color = Color(0xFF374151))
                        }
                    }

                    // 시크바
                    if (durationMs > 0) {
                        var slider by remember { mutableStateOf(0f) }
                        LaunchedEffect(positionMs, durationMs, isPlaying) {
                            if (isPlaying && durationMs > 0) slider =
                                positionMs / durationMs.toFloat()
                        }
                        Column {
                            Slider(
                                value = slider,
                                onValueChange = { slider = it },
                                onValueChangeFinished = {
                                    if (durationMs > 0) {
                                        val seek = (durationMs * slider).toInt()
                                        runCatching { player.seekTo(seek) }
                                        positionMs = seek
                                    }
                                }
                            )
                            Text(
                                "${ms(positionMs)} / ${ms(durationMs)}",
                                fontSize = 12.sp, color = Color(0xFF64748B)
                            )
                        }
                    }

                    // 감지 버튼
                    Button(
                        onClick = {
                            val uploadFile: File? = when {
                                recordedFile != null && recordedFile!!.exists() -> recordedFile
                                pickedUri != null -> copyUriToCache(ctx, pickedUri!!)
                                else -> null
                            }
                            if (uploadFile == null || !uploadFile.exists()) return@Button

                            scope.launch {
                                isDetecting = true
                                val first = runCatching { requestDetect(uploadFile, confirm = 0, phoneNumber = null) }
                                    .getOrNull()
                                isDetecting = false

                                if (first == null) return@launch

                                // 화면 표시용
                                first.probabilities?.let { p ->
                                    result = DetectionResult(
                                        real = (p.real ?: 0f) * 100f,
                                        fake2 = (p.fake_2 ?: 0f) * 100f,
                                        tts = (p.tts ?: 0f) * 100f
                                    )
                                }

                                // fake/tts만 추가 확인
                                val pred = (first.prediction ?: "").lowercase()
                                lastUploadFile = uploadFile
                                lastServerPhone = first.phoneNumber // 서버가 자동 할당했으면 재사용

                                if (pred != "real") {
                                    confirmTitle = "의심 통화 감지"
                                    confirmBody =
                                        "해당 통화에서 금전 요구나 개인정보 요구를 받았나요?\n" +
                                                "예(신고/저장) 를 누르면 DB에 저장되고 위험번호로 누적 관리됩니다."
                                    showConfirm = true
                                }
                            }
                        },
                        enabled = !isDetecting && (pickedUri != null || recordedFile != null),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .shadow(8.dp, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF9333EA), Color(0xFF2563EB))
                                    ),
                                    RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isDetecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    "지금 감지하세요",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // 결과
            AnimatedVisibility(
                visible = result != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                result?.let { r -> ResultCard(r) }
            }
        }
    }

    // 확인 팝업 (예=확정 저장, 아니오=저장 안함)
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        val f = lastUploadFile
                        scope.launch {
                            if (f != null && f.exists()) {
                                // 예 → confirm=1 로 재업로드 (DB 저장)
                                val second = runCatching {
                                    requestDetect(
                                        f,
                                        confirm = 1,
                                        phoneNumber = lastServerPhone // 서버 자동할당 번호 있으면 재전송
                                    )
                                }.getOrNull()

                                // 저장 완료 안내 (시스템 알림)
                                NotificationHelper.notifyResult(
                                    ctx,
                                    title = "신고 저장 완료",
                                    body = "의심 번호가 위험 번호 DB에 누적되었습니다."
                                )
                            }
                        }
                    }
                ) { Text("예(신고/저장)") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("아니오") }
            },
            title = { Text(confirmTitle) },
            text = { Text(confirmBody) }
        )
    }
}

/* ------------------- 네트워크 ------------------- */
private suspend fun requestDetect(
    file: File,
    confirm: Int,
    phoneNumber: String?
): PredictionResponse? = withContext(Dispatchers.IO) {
    val partAudio = MultipartBody.Part.createFormData(
        "audio", file.name, file.asRequestBody("audio/*".toMediaType())
    )
    val confirmRb: RequestBody = confirm.toString().toRequestBody("text/plain".toMediaType())
    val phoneRb: RequestBody? = phoneNumber?.toRequestBody("text/plain".toMediaType())

    val resp: Response<PredictionResponse> =
        ApiClient.api.uploadAudio(partAudio, confirmRb, phoneRb)

    if (!resp.isSuccessful) return@withContext null
    resp.body()
}

/* ------------------- 파일 유틸 ------------------- */
private fun copyUriToCache(ctx: Context, uri: Uri): File {
    val name = guessDisplayName(ctx.contentResolver, uri) ?: "audio_${System.currentTimeMillis()}.wav"
    val out = File(ctx.cacheDir, name)
    ctx.contentResolver.openInputStream(uri).use { input ->
        FileOutputStream(out).use { output ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val r = input?.read(buf) ?: -1
                if (r == -1) break
                output.write(buf, 0, r)
            }
            output.flush()
        }
    }
    return out
}

private fun guessDisplayName(cr: ContentResolver, uri: Uri): String? =
    cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }

private fun ms(v: Int): String {
    val s = v / 1000
    val m = s / 60
    val r = s % 60
    return "%d:%02d".format(m, r)
}

/* ------------------- 결과 UI ------------------- */
@Composable
fun ResultCard(result: DetectionResult) {
    val highest = result.getHighest()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        colors = CardDefaults.cardColors(Color.White)
    ) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(9999.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFFECFEFF), Color(0xFFFAF5FF))
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9999.dp))
                        .background(
                            when (highest.first) {
                                "real" -> Color(0xFFD1FAE5)
                                "fake_2" -> Color(0xFFFED7AA)
                                else -> Color(0xFFFEF3C7)
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        highest.first,
                        fontSize = 14.sp,
                        color = when (highest.first) {
                            "real" -> Color(0xFF047857)
                            "fake_2" -> Color(0xFFC2410C)
                            else -> Color(0xFFB45309)
                        },
                        fontWeight = FontWeight.Medium
                    )
                }
                Text("신뢰도 ${"%.1f".format(highest.second)}%", fontSize = 18.sp, color = Color(0xFF374151))
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ResultProgressBar("real", result.real, ResultType.REAL)
                ResultProgressBar("fake_2", result.fake2, ResultType.FAKE)
                ResultProgressBar("tts", result.tts, ResultType.TTS)
            }
        }
    }
}

@Composable
fun ResultProgressBar(label: String, value: Float, type: ResultType) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 16.sp, color = Color(0xFF374151))
            Text("${"%.1f".format(value)}%", fontSize = 16.sp, color = Color(0xFF64748B))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFE5E7EB))
        ) {
            Box(
                Modifier
                    .fillMaxWidth((value / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when (type) {
                            ResultType.REAL -> Brush.horizontalGradient(
                                listOf(Color(0xFF10B981), Color(0xFF14B8A6))
                            )
                            ResultType.FAKE -> Brush.horizontalGradient(
                                listOf(Color(0xFFF97316), Color(0xFFEF4444))
                            )
                            ResultType.TTS -> Brush.horizontalGradient(
                                listOf(Color(0xFFF59E0B), Color(0xFFF97316))
                            )
                        }
                    )
            )
        }
    }
}

data class DetectionResult(val real: Float, val fake2: Float, val tts: Float) {
    fun getHighest(): Pair<String, Float> =
        when {
            real >= fake2 && real >= tts -> "real" to real
            fake2 >= real && fake2 >= tts -> "fake_2" to fake2
            else -> "tts" to tts
        }
}
enum class ResultType { REAL, FAKE, TTS }

/* --------------- AudioRecord 래퍼 --------------- */
private class AudioRecorder {
    private var impl: PcmWavRecorder? = null
    fun start(ctx: Context) { impl = PcmWavRecorder(ctx).also { it.start() } }

    fun stop(): File? {
        val i = impl ?: return null
        impl = null
        return try {
            val m = i::class.java.getMethod("stop")
            toFileOrNull(m.invoke(i))
        } catch (_: NoSuchMethodException) {
            try {
                val m2 = i::class.java.getMethod("stopAndGetWav")
                toFileOrNull(m2.invoke(i))
            } catch (_: Throwable) {
                null
            }
        } catch (_: Throwable) { null }
    }

    private fun toFileOrNull(any: Any?): File? = when (any) {
        is File -> if (any.exists()) any else null
        is String -> if (any.isNotBlank()) {
            val f = File(any); if (f.exists()) f else null
        } else null
        else -> null
    }
}

private suspend fun startRecord(
    ctx: Context,
    rec: AudioRecorder,
    onToggle: (Boolean) -> Unit
) = withContext(Dispatchers.IO) {
    runCatching { rec.start(ctx) }
    onToggle(true)
}

private suspend fun stopRecord(rec: AudioRecorder): File? = withContext(Dispatchers.IO) {
    runCatching { rec.stop() }.getOrNull()
}
