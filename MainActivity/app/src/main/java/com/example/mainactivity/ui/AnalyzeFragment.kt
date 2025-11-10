package com.example.mainactivity.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.mainactivity.R
import com.example.mainactivity.network.ApiClient
import com.example.mainactivity.network.PredictionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AnalyzeFragment : Fragment() {

    // ========= 상태 =========
    private var pickedUri: Uri? = null
    private var recordingPath: String? = null
    private var player: MediaPlayer? = null

    // AudioRecord (WAV 녹음)
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var pcmFile: File? = null
    private var wavFile: File? = null

    // ========= UI =========
    private lateinit var tvStatus: TextView
    private lateinit var btnPick: Button
    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var btnAnalyze: Button
    private lateinit var progress: ProgressBar

    // ========= 권한 런처 =========
    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.entries.any { !it.value }) {
            Toast.makeText(requireContext(), getString(R.string.permission_needed), Toast.LENGTH_SHORT).show()
        }
    }

    // 파일 선택 런처
    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        pickedUri = uri
        recordingPath = null
        tvStatus.text = getString(R.string.file_selected, uri.toString())
        btnPlay.isEnabled = true
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_analyze, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvStatus   = view.findViewById(R.id.tvStatus)
        btnPick    = view.findViewById(R.id.btnPick)
        btnRecord  = view.findViewById(R.id.btnRecord)
        btnPlay    = view.findViewById(R.id.btnPlay)
        btnAnalyze = view.findViewById(R.id.btnAnalyze)
        progress   = view.findViewById(R.id.progress)

        btnPlay.isEnabled = false
        askPermissionsIfNeeded()

        btnPick.setOnClickListener   { pickAudio.launch("audio/*") }
        btnRecord.setOnClickListener { if (!isRecording.get()) startRecordingWav() else stopRecordingWav() }
        btnPlay.setOnClickListener   { playSelected() }
        btnAnalyze.setOnClickListener { analyzeSelected() }
    }

    // ---------------------------------------------------------
    // 권한
    // ---------------------------------------------------------
    private fun hasAudioPermissions(): Boolean {
        val recOk = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val readOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        val notiOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return recOk && readOk && notiOk
    }

    private fun askPermissionsIfNeeded() {
        val req = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            req += Manifest.permission.READ_MEDIA_AUDIO
            req += Manifest.permission.POST_NOTIFICATIONS
        } else {
            req += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val need = req.any {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (need) requestPerms.launch(req.toTypedArray())
    }

    // ---------------------------------------------------------
    // WAV 녹음 (AudioRecord)
    // ---------------------------------------------------------
    companion object {
        private const val SAMPLE_RATE_HZ    = 16_000
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING    = AudioFormat.ENCODING_PCM_16BIT
    }

    private val MIN_BUF_SIZE: Int = AudioRecord.getMinBufferSize(
        SAMPLE_RATE_HZ, CHANNEL_CONFIG_IN, AUDIO_ENCODING
    )

    @SuppressLint("MissingPermission") // 사전에 hasAudioPermissions()로 체크
    private fun startRecordingWav() {
        if (!hasAudioPermissions()) {
            askPermissionsIfNeeded()
            Toast.makeText(requireContext(), getString(R.string.permission_needed), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            pcmFile = File(requireContext().cacheDir, "rec_${System.currentTimeMillis()}.pcm")
            wavFile = File(requireContext().cacheDir, "rec_${System.currentTimeMillis()}.wav")
            pickedUri = null
            recordingPath = null

            val bufferSize = maxOf(MIN_BUF_SIZE, 2048)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG_IN,
                AUDIO_ENCODING,
                bufferSize
            )

            val out = DataOutputStream(BufferedOutputStream(FileOutputStream(pcmFile!!)))

            isRecording.set(true)
            audioRecord?.startRecording()

            recordThread = Thread {
                val shortBuf = ShortArray(bufferSize / 2)
                try {
                    while (isRecording.get()) {
                        val read = audioRecord?.read(shortBuf, 0, shortBuf.size) ?: 0
                        if (read > 0) {
                            val bb = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until read) bb.putShort(shortBuf[i])
                            out.write(bb.array())
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { out.flush() } catch (_: Exception) {}
                    try { out.close() } catch (_: Exception) {}
                }
            }.also { it.start() }

            tvStatus.text = getString(R.string.recording)
            btnRecord.text = getString(R.string.stop_recording)
            btnPlay.isEnabled = false

        } catch (e: Exception) {
            e.printStackTrace()
            isRecording.set(false)
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
            Toast.makeText(requireContext(), getString(R.string.record_fail, e.message ?: "unknown"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingWav() {
        try {
            isRecording.set(false)
            audioRecord?.apply {
                try { stop() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
            audioRecord = null
            recordThread?.join(400)
            recordThread = null

            val pcm = pcmFile
            val wav = wavFile
            if (pcm != null && wav != null && pcm.exists()) {
                pcmToWav(pcm, wav, SAMPLE_RATE_HZ, 1, 16)
                recordingPath = wav.absolutePath
                tvStatus.text = getString(R.string.record_done, recordingPath)
                btnRecord.text = getString(R.string.start_recording)
                btnPlay.isEnabled = true
                try { pcm.delete() } catch (_: Exception) {}
            } else {
                tvStatus.text = getString(R.string.record_stop_fail, "no pcm")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), getString(R.string.record_stop_fail, e.message ?: "unknown"), Toast.LENGTH_SHORT).show()
        }
    }

    /** 간단 WAV 헤더 작성 + 바디 복사 */
    private fun pcmToWav(pcm: File, wav: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val pcmLen = pcm.length().toInt()
        val dataLen = pcmLen + 36
        DataInputStream(BufferedInputStream(FileInputStream(pcm))).use { input ->
            DataOutputStream(BufferedOutputStream(FileOutputStream(wav))).use { out ->
                out.writeBytes("RIFF")
                out.write(intToLE(dataLen))
                out.writeBytes("WAVE")
                out.writeBytes("fmt ")
                out.write(intToLE(16))
                out.write(shortToLE(1)) // PCM
                out.write(shortToLE(channels.toShort()))
                out.write(intToLE(sampleRate))
                val byteRate = sampleRate * channels * bitsPerSample / 8
                out.write(intToLE(byteRate))
                val blockAlign = (channels * bitsPerSample / 8).toShort()
                out.write(shortToLE(blockAlign))
                out.write(shortToLE(bitsPerSample.toShort()))
                out.writeBytes("data")
                out.write(intToLE(pcmLen))

                val buf = ByteArray(4096)
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    out.write(buf, 0, r)
                }
                out.flush()
            }
        }
    }

    private fun intToLE(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        (v shr 8 and 0xff).toByte(),
        (v shr 16 and 0xff).toByte(),
        (v shr 24 and 0xff).toByte()
    )

    private fun shortToLE(v: Short) = byteArrayOf(
        (v.toInt() and 0xff).toByte(),
        (v.toInt() shr 8 and 0xff).toByte()
    )

    // ---------------------------------------------------------
    // 재생
    // ---------------------------------------------------------
    private fun playSelected() {
        try {
            player?.release()
            player = MediaPlayer()
            when {
                pickedUri != null     -> player?.setDataSource(requireContext(), pickedUri!!)
                recordingPath != null -> player?.setDataSource(recordingPath)
                else -> {
                    Toast.makeText(requireContext(), getString(R.string.no_play_source), Toast.LENGTH_SHORT).show()
                    return
                }
            }
            player?.setOnPreparedListener { it.start() }
            player?.setOnCompletionListener {
                Toast.makeText(requireContext(), getString(R.string.play_done), Toast.LENGTH_SHORT).show()
            }
            player?.prepareAsync()
        } catch (e: SecurityException) {
            askPermissionsIfNeeded()
            Toast.makeText(requireContext(), getString(R.string.permission_needed), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), getString(R.string.play_fail, e.message ?: "unknown"), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------
    // 서버 업로드 (suspend API 사용 / probabilities null 안전)
    // ---------------------------------------------------------
    private fun analyzeSelected() {
        val originFile: File? = when {
            pickedUri != null     -> null
            recordingPath != null -> File(recordingPath!!)
            else -> null
        }

        progress.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.uploading)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1) 파일 준비 (IO 스레드)
                val file = withContext(Dispatchers.IO) {
                    if (originFile != null && originFile.exists()) {
                        originFile
                    } else {
                        pickedUri?.let { copyUriToCacheIO(it) }
                    }
                }

                if (file == null || !file.exists()) {
                    progress.visibility = View.GONE
                    tvStatus.text = getString(R.string.no_upload_source)
                    return@launch
                }

                // 2) Multipart 생성
                val mediaType = "audio/*".toMediaType()
                val reqBody: RequestBody = file.asRequestBody(mediaType)
                val part = MultipartBody.Part.createFormData("audio", file.name, reqBody)

                // 3) 네트워크 호출 (suspend)
                val resp = ApiClient.api.uploadAudio(part)

                progress.visibility = View.GONE

                if (!resp.isSuccessful) {
                    tvStatus.text = getString(R.string.server_error_code, resp.code())
                    return@launch
                }

                val body: PredictionResponse? = resp.body()
                if (body == null) {
                    tvStatus.text = getString(R.string.response_parse_fail)
                    return@launch
                }

                val label  = body.prediction ?: "unknown"
                val conf   = body.confidence ?: 0f
                val probs  = body.probabilities
                val pReal  = probs?.real   ?: 0f
                val pFake2 = probs?.fake_2 ?: 0f
                val pTts   = probs?.tts    ?: 0f

                tvStatus.text = getString(
                    R.string.result_template,
                    label,
                    conf,
                    pReal,
                    pFake2,
                    pTts
                )

                // ✅ fake 결과일 때만 알림/팝업
                if (!label.equals("real", ignoreCase = true)) {
                    showFakeAlert(label, conf.toDouble())
                }

            } catch (e: Exception) {
                progress.visibility = View.GONE
                tvStatus.text = getString(R.string.network_fail, e.message ?: "unknown")
            }
        }
    }

    /** IO 스레드에서 Uri → 캐시 복사 */
    private suspend fun copyUriToCacheIO(uri: Uri): File? = withContext(Dispatchers.IO) {
        return@withContext try {
            val fileName = guessFileName(uri) ?: "upload_audio_${System.currentTimeMillis()}.wav"
            val outFile = File(requireContext().cacheDir, fileName)
            requireContext().contentResolver.openInputStream(uri).use { input ->
                FileOutputStream(outFile).use { output ->
                    if (input != null) {
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r == -1) break
                            output.write(buf, 0, r)
                        }
                        output.flush()
                    }
                }
            }
            outFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun guessFileName(uri: Uri): String? {
        return try {
            val cr: ContentResolver = requireContext().contentResolver
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { player?.release() } catch (_: Exception) {}
        player = null

        if (isRecording.get()) stopRecordingWav()
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    // ---------------------------------------------------------
    // 🔔 fake 결과 알림 + ⚠️ 다이얼로그 동시 표시
    // ---------------------------------------------------------
    @Suppress("MissingPermission")
    private fun showFakeAlert(prediction: String, confidence: Double) {
        val (title, message) = when (prediction) {
            "fake_2" -> "⚠️ 딥페이크 음성 감지" to
                    "딥페이크 음성으로 의심됩니다.\n신뢰도: ${"%.2f".format(confidence * 100)}%"
            "tts" -> "🗣️ 합성 음성(TTS) 감지" to
                    "AI 합성음으로 추정됩니다.\n신뢰도: ${"%.2f".format(confidence * 100)}%"
            else -> "알 수 없는 결과" to prediction
        }

        // (1) 앱 내부 AlertDialog
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("확인") { dialog: DialogInterface, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()

        // (2) 시스템 Notification
        val context = requireContext()
        val channelId = "deepfake_alert_channel"
        val manager = context.getSystemService(android.app.NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "딥페이크 경고",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "딥페이크 또는 합성음 감지 시 알림"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val smallIconRes = runCatching {
            val id = resources.getIdentifier("ic_warning", "drawable", requireContext().packageName)
            if (id != 0) id else R.mipmap.ic_launcher
        }.getOrDefault(R.mipmap.ic_launcher)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
