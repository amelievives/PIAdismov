package com.example.piadismov

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Publica el WAV en el historial cuando ya tiene un encabezado válido. */
class WavWriter(private val pendingFile: File, private val sampleRate: Int = 16000) {
    private val raf = RandomAccessFile(pendingFile, "rw")
    private var dataLen = 0

    init {
        try {
            raf.write(ByteArray(44)) // Espacio para el encabezado que se llena en close().
        } catch (e: IOException) {
            runCatching { raf.close() }
            throw e
        }
    }

    fun write(buf: ShortArray, n: Int, offset: Int = 0) {
        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in offset until offset + n) bb.putShort(buf[i])
        raf.write(bb.array())
        dataLen += n * 2
    }

    fun close() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(36 + dataLen)
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1.toShort())                 // PCM
        h.putShort(1.toShort())                 // mono
        h.putInt(sampleRate)
        h.putInt(sampleRate * 2)                // bytes por segundo
        h.putShort(2.toShort())                 // block align
        h.putShort(16.toShort())                // bits por muestra
        h.put("data".toByteArray())
        h.putInt(dataLen)
        raf.use { output ->
            output.setLength(44L + dataLen)
            output.seek(0)
            output.write(h.array())
        }

        val finishedFile = File(pendingFile.parentFile, "${pendingFile.nameWithoutExtension}.wav")
        if (finishedFile.exists() || !pendingFile.renameTo(finishedFile)) {
            throw IOException("No se pudo finalizar la grabación")
        }
    }
}
