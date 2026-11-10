package com.example.piadismov

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Escribe audio PCM 16 bits mono en un archivo .wav, actualizando el encabezado al cerrar. */
class WavWriter(file: File, private val sampleRate: Int = 16000) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataLen = 0

    init {
        raf.setLength(0)
        raf.write(ByteArray(44)) // espacio para el encabezado, se llena en close()
    }

    fun write(buf: ShortArray, n: Int) {
        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putShort(buf[i])
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
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }
}
