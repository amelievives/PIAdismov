package com.example.piadismov

/** Conserva las muestras más recientes en memoria mientras la escucha está activa. */
internal class PcmHistory(capacity: Int) {
    private val samples = ShortArray(capacity)
    private var next = 0
    private var count = 0

    fun append(source: ShortArray, length: Int) {
        val kept = minOf(length, samples.size)
        val start = length - kept
        val first = minOf(kept, samples.size - next)
        source.copyInto(samples, next, start, start + first)
        source.copyInto(samples, 0, start + first, length)
        next = (next + kept) % samples.size
        count = minOf(count + kept, samples.size)
    }

    fun writeTo(writer: WavWriter) {
        val start = (next - count + samples.size) % samples.size
        val first = minOf(count, samples.size - start)
        writer.write(samples, first, start)
        writer.write(samples, count - first)
    }

    fun clear() {
        next = 0
        count = 0
    }
}
