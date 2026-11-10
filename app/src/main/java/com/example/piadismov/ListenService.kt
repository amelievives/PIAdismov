package com.example.piadismov

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servicio en primer plano que:
 *  1) escucha el micrófono de forma continua,
 *  2) detecta la palabra clave con Vosk (sin internet),
 *  3) al oírla, guarda el audio en un .wav,
 *  4) la grabación termina al decir la palabra otra vez, al tocar "Detener" o a los 30 min,
 *  5) vuelve a escuchar.
 */
class ListenService : Service() {

    companion object {
        const val ACTION_START = "com.example.palabraclave.START"
        const val ACTION_STOP = "com.example.palabraclave.STOP"
        const val ACTION_STOP_RECORDING = "com.example.palabraclave.STOP_RECORDING"
        const val SAMPLE_RATE = 16000

        private const val CHANNEL = "escucha"
        private const val NOTIF_ID = 1
        private const val MODEL_ASSET = "model-es"
        private const val TAG = "ListenService"

        /** Tope de seguridad por grabación. */
        private const val MAX_RECORDING_MS = 30 * 60 * 1000L

        /** Al empezar a grabar, se ignora la palabra durante este tiempo (para no cortar de inmediato). */
        private const val STOP_WORD_GUARD_MS = 3000L

        /** Tras detener, pausa corta antes de volver a detectar. */
        private const val COOLDOWN_MS = 1500L

        fun recordingsDir(c: Context): File {
            val base = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: c.filesDir
            return File(base, "grabaciones").also { it.mkdirs() }
        }
    }

    @Volatile private var running = false
    @Volatile private var stopRecordingRequested = false
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL, "Escucha de palabra clave", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                if (worker?.isAlive != true) {
                    Prefs.setStatus(this, Prefs.STATUS_IDLE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP_RECORDING -> {
                stopRecordingRequested = true
                if (worker?.isAlive != true) {
                    Prefs.setStatus(this, Prefs.STATUS_IDLE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        // Obligatorio llamar a startForeground rápido tras startForegroundService.
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID,
                buildNotification(Prefs.STATUS_LOADING, Prefs.getKeyword(this), 0L),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar en primer plano", e)
            toast("No se pudo activar el micrófono")
            Prefs.setStatus(this, Prefs.STATUS_IDLE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Falta el permiso de micrófono")
            Prefs.setStatus(this, Prefs.STATUS_IDLE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            worker = Thread({ loop() }, "escucha-loop").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- bucle principal

    private fun loop() {
        var model: Model? = null
        var rec: Recognizer? = null
        var audio: AudioRecord? = null
        var wav: WavWriter? = null
        try {
            Prefs.setStatus(this, Prefs.STATUS_LOADING)

            val keyword = Prefs.getKeyword(this).lowercase(Locale.ROOT).trim()
            require(keyword.isNotEmpty()) { "La palabra clave está vacía" }
            updateNotification(Prefs.STATUS_LOADING, keyword, 0L)

            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(prepareModel().absolutePath)

            // Vocabulario completo (sin gramática restringida): así las demás palabras se
            // reconocen como lo que son y no se confunden con tu palabra clave.
            rec = Recognizer(model, SAMPLE_RATE.toFloat())

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audio = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE)
            )
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "No se pudo abrir el micrófono" }
            audio.startRecording()

            Prefs.setStatus(this, Prefs.STATUS_LISTENING)
            updateNotification(Prefs.STATUS_LISTENING, keyword, 0L)

            val buf = ShortArray(2048)
            var recStart = 0L
            var ignoreUntil = 0L

            while (running) {
                val n = audio.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                val now = System.currentTimeMillis()

                if (wav == null) {
                    // MODO ESCUCHA: buscar la palabra
                    if (now < ignoreUntil) continue
                    // Solo se evalúa cuando Vosk da un resultado FINAL (la frase terminó).
                    if (rec.acceptWaveForm(buf, n) && heardKeyword(rec.result, keyword)) {
                        rec.reset()
                        stopRecordingRequested = false
                        wav = WavWriter(newRecordingFile(), SAMPLE_RATE)
                        recStart = now
                        wav.write(buf, n)
                        Prefs.setStatus(this, Prefs.STATUS_RECORDING)
                        updateNotification(Prefs.STATUS_RECORDING, keyword, recStart)
                    }
                } else {
                    // MODO GRABACIÓN: guardar audio hasta que pidan detener
                    wav.write(buf, n)
                    val elapsed = now - recStart
                    var stop = stopRecordingRequested || elapsed >= MAX_RECORDING_MS

                    // Decir la palabra otra vez también detiene (pasado un margen inicial).
                    if (!stop && elapsed >= STOP_WORD_GUARD_MS) {
                        if (rec.acceptWaveForm(buf, n) && heardKeyword(rec.result, keyword)) stop = true
                    }

                    if (stop) {
                        wav.close(); wav = null
                        rec.reset()
                        stopRecordingRequested = false
                        ignoreUntil = now + COOLDOWN_MS
                        Prefs.setStatus(this, Prefs.STATUS_LISTENING)
                        updateNotification(Prefs.STATUS_LISTENING, keyword, 0L)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error en el bucle", e)
            toast("Error: ${e.message}")
        } finally {
            try { wav?.close() } catch (_: Exception) {}
            try { audio?.stop() } catch (_: Exception) {}
            try { audio?.release() } catch (_: Exception) {}
            try { rec?.close() } catch (_: Exception) {}
            try { model?.close() } catch (_: Exception) {}
            running = false
            Prefs.setStatus(this, Prefs.STATUS_IDLE)
            stopSelf()
        }
    }

    /** True si la frase reconocida contiene la palabra clave COMPLETA (no como parte de otra). */
    private fun heardKeyword(json: String, keyword: String): Boolean {
        val text = JSONObject(json).optString("text", "").trim()
        if (text.isEmpty()) return false
        Log.d(TAG, "oído: «$text»")
        return " $text ".contains(" $keyword ")
    }

    private fun newRecordingFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir(this), "rec_$stamp.wav")
    }

    // ---------------------------------------------------------------- modelo Vosk

    /** Copia assets/model-es a almacenamiento interno la primera vez (Vosk necesita una ruta real). */
    private fun prepareModel(): File {
        val dest = File(filesDir, MODEL_ASSET)
        val marker = File(dest, ".ok")
        if (!marker.exists()) {
            check(!assets.list(MODEL_ASSET).isNullOrEmpty()) {
                "Falta la carpeta app/src/main/assets/$MODEL_ASSET (modelo de Vosk)"
            }
            dest.deleteRecursively()
            copyAssets(MODEL_ASSET, dest)
            marker.createNewFile()
        }
        return dest
    }

    private fun copyAssets(path: String, dest: File) {
        val children = assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (name in children) copyAssets("$path/$name", File(dest, name))
        }
    }

    // ---------------------------------------------------------------- notificación

    private fun buildNotification(state: String, keyword: String, since: Long): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        fun serviceIntent(code: Int, action: String) = PendingIntent.getService(
            this, code, Intent(this, ListenService::class.java).setAction(action), flags
        )

        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), flags)

        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setColor(ContextCompat.getColor(this, R.color.pc_accent))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (state) {
            Prefs.STATUS_RECORDING -> b
                .setContentTitle("Grabando")
                .setContentText("Di «$keyword» o toca para detener")
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setWhen(since)
                .addAction(0, "Detener grabación", serviceIntent(1, ACTION_STOP_RECORDING))
                .addAction(0, "Apagar", serviceIntent(2, ACTION_STOP))

            Prefs.STATUS_LISTENING -> b
                .setContentTitle("Micrófono activo")
                .setContentText("Di «$keyword» para empezar a grabar")
                .setShowWhen(false)
                .addAction(0, "Apagar", serviceIntent(2, ACTION_STOP))

            else -> b
                .setContentTitle("Preparando…")
                .setContentText("Cargando el modelo de voz")
                .setShowWhen(false)
        }
        return b.build()
    }

    private fun updateNotification(state: String, keyword: String, since: Long) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(state, keyword, since))
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
