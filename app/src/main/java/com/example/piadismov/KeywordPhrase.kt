package com.example.piadismov

import java.text.Normalizer
import java.util.Locale

internal class KeywordPhrase(value: String) {
    private val wanted = words(value)
    val isValid = wanted.any { word -> word.any(Char::isLetter) }

    fun matches(transcript: String): Boolean {
        if (!isValid) return false
        val heard = words(transcript)
        return (0..(heard.size - wanted.size)).any { start ->
            wanted.indices.all { index -> heard[start + index] == wanted[index] }
        }
    }

    companion object {
        private val tokens = Regex("[\\p{L}\\p{M}\\p{N}]+")

        private fun words(text: String): List<String> {
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
                .lowercase(Locale.ROOT)
                // Admite palabras escritas sin tilde y mantiene la distinción entre n y ñ.
                .replace('á', 'a').replace('é', 'e').replace('í', 'i')
                .replace('ó', 'o').replace('ú', 'u').replace('ü', 'u')
            return tokens.findAll(normalized).map { it.value }.toList()
        }
    }
}
