package com.example.piadismov

import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

internal class VoiceKeywordDetector(model: Model, sampleRate: Int, keyword: String) : AutoCloseable {
    private val phrase = KeywordPhrase(keyword)
    private val quietLimit = sampleRate * 7 / 5
    private var receivedSpeech = false
    private var quietSamples = 0
    private val vocabulary = Recognizer(model, sampleRate.toFloat())
    private val focused = try {
        if (phrase.matches(DEFAULT_KEYWORD)) {
            val grammar = JSONArray(listOf(DEFAULT_KEYWORD, "grabado", "[unk]")).toString()
            Recognizer(model, sampleRate.toFloat(), grammar).apply { setWords(true) }
        } else {
            null
        }
    } catch (e: Exception) {
        vocabulary.close()
        throw e
    }

    fun accept(samples: ShortArray, count: Int): Boolean {
        val flush = reachedQuietPause(samples, count)
        val transcript = result(vocabulary, samples, count, flush)
        if (transcript != null && phrase.matches(transcript.optString("text"))) {
            return true
        }

        val command = focused ?: return false
        val result = result(command, samples, count, false) ?: return false

        // La gramática corta puede confundir sonidos parecidos. Solo aceptamos
        // una palabra aislada con puntuación alta y suficiente duración.
        if (result.optString("text") != DEFAULT_KEYWORD) return false
        val word = result.optJSONArray("result")?.optJSONObject(0) ?: return false
        return word.optDouble("conf") >= 0.995 &&
            word.optDouble("end") - word.optDouble("start") >= 0.24
    }

    fun reset() {
        vocabulary.reset()
        focused?.reset()
        receivedSpeech = false
        quietSamples = 0
    }

    private fun result(recognizer: Recognizer, samples: ShortArray, count: Int, flush: Boolean): JSONObject? {
        val json = when {
            recognizer.acceptWaveForm(samples, count) -> recognizer.result
            flush -> recognizer.finalResult
            else -> return null
        }
        return JSONObject(json)
    }

    // Si Vosk no cierra la frase tras una pausa muy silenciosa, recupera su resultado final.
    private fun reachedQuietPause(samples: ShortArray, count: Int): Boolean {
        var power = 0L
        for (index in 0 until count) {
            val sample = samples[index].toLong()
            power += sample * sample
        }
        if (power > 150L * 150 * count) {
            receivedSpeech = true
            quietSamples = 0
        } else if (receivedSpeech) {
            quietSamples += count
        }
        if (quietSamples < quietLimit) return false
        receivedSpeech = false
        quietSamples = 0
        return true
    }

    override fun close() {
        try {
            focused?.close()
        } finally {
            vocabulary.close()
        }
    }

    private companion object {
        const val DEFAULT_KEYWORD = "grabar"
    }
}
