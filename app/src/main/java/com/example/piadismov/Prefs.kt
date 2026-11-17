package com.example.piadismov

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Guarda la palabra clave y el estado actual del servicio. */
object Prefs {
    const val STATUS_IDLE = "idle"
    const val STATUS_LOADING = "loading"
    const val STATUS_LISTENING = "listening"
    const val STATUS_RECORDING = "recording"

    private val processSession = UUID.randomUUID().toString() + ":"
    @Volatile private var activeKeyword: String? = null

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("palabra_clave", Context.MODE_PRIVATE)

    fun getKeyword(c: Context): String = prefs(c).getString("keyword", "grabar") ?: "grabar"
    fun setKeyword(c: Context, v: String) = prefs(c).edit().putString("keyword", v).apply()

    fun getStatus(c: Context): String {
        val saved = prefs(c).getString("status", "").orEmpty()
        return if (saved.startsWith(processSession)) saved.removePrefix(processSession) else STATUS_IDLE
    }

    fun getWidgetKeyword(c: Context): String = activeKeyword ?: getKeyword(c)

    /** Cambia el estado y refresca el widget. La Activity se entera por el listener de prefs. */
    fun setStatus(c: Context, s: String, keyword: String? = null) {
        if (s == STATUS_IDLE || keyword != null) activeKeyword = keyword
        prefs(c).edit().putString("status", processSession + s).apply()
        WordWidget.refresh(c)
    }
}

