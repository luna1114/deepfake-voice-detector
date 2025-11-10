package com.example.mainactivity.ui.detect

import android.Manifest
import android.content.Context
import android.media.*
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 16kHz, MONO, PCM16 → WAV 저장용 레코더
 * - start(): 권한(RECORD_AUDIO) 체크 후 시작, 성공 시 true
 * - stop(): WAV 파일 완성 후 파일 경로 반환 (실패 시 null)
 */
class PcmWavRecorder(
    private val ctx: Context,
    private val sr: Int = 16_000,
    private val ch: Int = AudioFormat.CHANNEL_IN_MONO,
    private val bits: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null

    private var pcmFile: File? = null
    private var wavFile: File? = null

    /** 권한이 없으면 false 를 반환. */
    fun start(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false

        val minBuf = AudioRecord.getMinBufferSize(sr, ch, bits)
        val bufferSize = maxOf(minBuf, 4096)

        pcmFile = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.pcm")
        wavFile = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.wav")

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sr,
            ch,
            bits,
            bufferSize
        )

        val out = DataOutputStream(BufferedOutputStream(FileOutputStream(pcmFile!!)))

        isRecording.set(true)
        try {
            audioRecord?.startRecording()
        } catch (se: SecurityException) {
            // 혹시 모를 OEM 이슈 대비
            isRecording.set(false)
            try { out.close() } catch (_: Exception) {}
            releaseInternal()
            return false
        }

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

        return true
    }

    /** 녹음 종료 후 WAV 파일 완성, 경로 반환 (실패 시 null) */
    fun stop(): String? {
        isRecording.set(false)
        try {
            audioRecord?.apply {
                try { stop() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            audioRecord = null
        }

        try { recordThread?.join(400) } catch (_: Exception) {}
        recordThread = null

        val pcm = pcmFile
        val wav = wavFile
        if (pcm == null || wav == null || !pcm.exists()) {
            releaseInternal()
            return null
        }

        return try {
            pcmToWav(pcm, wav, sr, 1, 16)
            try { pcm.delete() } catch (_: Exception) {}
            wav.absolutePath
        } catch (_: Exception) {
            releaseInternal()
            null
        }
    }

    fun isRecording(): Boolean = isRecording.get()

    private fun releaseInternal() {
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    /** 간단 WAV 헤더 작성 + PCM 바디 복사 */
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
}
