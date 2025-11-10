package com.example.mainactivity.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * PCM 캡처 → WAV 파일로 저장
 */
class PcmWavRecorder(
    private val outFile: File,
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    @Volatile private var isRecording = false
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var totalPcmBytes: Long = 0

    fun start() {
        if (isRecording) return
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuf
        )
        audioRecord?.startRecording()
        isRecording = true
        totalPcmBytes = 0

        recordThread = thread(start = true) {
            FileOutputStream(outFile).use { fos ->
                // WAV 헤더자리(44바이트) 비워두고 나중에 갱신
                val header = ByteArray(44)
                fos.write(header)

                val buf = ByteArray(minBuf)
                while (isRecording) {
                    val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                    if (read > 0) {
                        fos.write(buf, 0, read)
                        totalPcmBytes += read
                    }
                }
                // 헤더 갱신
                fos.flush()
                updateWavHeader(outFile, totalPcmBytes, sampleRate, 1, 16)
            }
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        try { recordThread?.join() } catch (_: Exception) {}
        recordThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun updateWavHeader(file: File, pcmBytes: Long, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val totalDataLen = pcmBytes + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen.toInt())
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1) // AudioFormat PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitsPerSample / 8).toShort()) // BlockAlign
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(pcmBytes.toInt())

        val raf = file.randomAccessFile("rw")
        raf.seek(0)
        raf.write(buffer.array())
        raf.close()
    }

    // 간단 randomAccessFile 확장
    private fun File.randomAccessFile(mode: String) =
        java.io.RandomAccessFile(this, mode)
}
