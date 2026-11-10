package com.example.piadismov

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/**
 * Widget con un botón redondo:
 *  - apagado   → micrófono: activa la escucha
 *  - escuchando → power: apaga la escucha
 *  - grabando  → cuadrado: detiene la grabación (sigue escuchando)
 */
class WordWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    companion object {
        /** Redibuja todos los widgets colocados (se llama cuando cambia el estado). */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, WordWidget::class.java))
            ids.forEach { update(app, manager, it) }
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val status = Prefs.getStatus(context)
            val views = RemoteViews(context.packageName, R.layout.widget_word)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

            fun service(code: Int, action: String) = PendingIntent.getService(
                context, code, Intent(context, ListenService::class.java).setAction(action), flags
            )

            val statusText: String
            val icon: Int
            val circle: Int
            val desc: String
            val action: PendingIntent

            when (status) {
                Prefs.STATUS_RECORDING -> {
                    statusText = "● GRABANDO"
                    icon = R.drawable.ic_stop
                    circle = R.drawable.bg_circle_accent
                    desc = "Detener grabación"
                    action = service(2, ListenService.ACTION_STOP_RECORDING)
                }
                Prefs.STATUS_LISTENING, Prefs.STATUS_LOADING -> {
                    statusText = if (status == Prefs.STATUS_LOADING) "CARGANDO…" else "ESCUCHANDO"
                    icon = R.drawable.ic_power
                    circle = R.drawable.bg_circle_neutral
                    desc = "Apagar escucha"
                    action = service(1, ListenService.ACTION_STOP)
                }
                else -> {
                    statusText = "APAGADO"
                    icon = R.drawable.ic_mic
                    circle = R.drawable.bg_circle_accent
                    desc = "Activar escucha"
                    val hasMic = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    action = if (hasMic) {
                        PendingIntent.getForegroundService(
                            context, 0,
                            Intent(context, ListenService::class.java).setAction(ListenService.ACTION_START),
                            flags
                        )
                    } else {
                        // Sin permiso de micrófono: abrir la app para que lo pida.
                        PendingIntent.getActivity(
                            context, 3, Intent(context, MainActivity::class.java), flags
                        )
                    }
                }
            }

            views.setTextViewText(R.id.widget_status, statusText)
            views.setTextViewText(R.id.widget_word, "“${Prefs.getKeyword(context)}”")
            views.setImageViewResource(R.id.widget_button, icon)
            views.setInt(R.id.widget_button, "setBackgroundResource", circle)
            views.setContentDescription(R.id.widget_button, desc)
            views.setOnClickPendingIntent(R.id.widget_button, action)

            // Tocar el texto abre la app.
            views.setOnClickPendingIntent(
                R.id.widget_body,
                PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java), flags)
            )

            manager.updateAppWidget(id, views)
        }
    }
}
