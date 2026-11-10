package com.example.piadismov

import android.content.Context
import android.content.SharedPreferences

/** Guarda la palabra clave y el estado actual del servicio. */
object Prefs {
    const val STATUS_IDLE = "idle"
    const val STATUS_LOADING = "loading"
    const val STATUS_LISTENING = "listening"
    const val STATUS_RECORDING = "recording"

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("palabra_clave", Context.MODE_PRIVATE)

    fun getKeyword(c: Context): String = prefs(c).getString("keyword", "grabar") ?: "grabar"
    fun setKeyword(c: Context, v: String) = prefs(c).edit().putString("keyword", v).apply()

    fun getStatus(c: Context): String = prefs(c).getString("status", STATUS_IDLE) ?: STATUS_IDLE

    /** Cambia el estado y refresca el widget. La Activity se entera por el listener de prefs. */
    fun setStatus(c: Context, s: String) {
        prefs(c).edit().putString("status", s).apply()
        WordWidget.refresh(c)
    }
}

