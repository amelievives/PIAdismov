package com.example.piadismov

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
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

        private const val PRE_ROLL_SECONDS = 3

        fun recordingsDir(c: Context): File {
            val base = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: c.filesDir
            return File(base, "grabaciones").also { it.mkdirs() }
        }
    }

    @Volatile private var running = false
    @Volatile private var stopRecordingRequested = false
    private var worker: Thread? = null
    private var destroyed = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "Escucha de palabra clave", NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                if (worker == null) {
                    Prefs.setStatus(this, Prefs.STATUS_IDLE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP_RECORDING -> {
                stopRecordingRequested = true
                if (worker == null) {
                    Prefs.setStatus(this, Prefs.STATUS_IDLE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        if (worker != null) return START_NOT_STICKY
        val keyword = Prefs.getKeyword(this)

        // Obligatorio llamar a startForeground rápido tras startForegroundService.
        try {
            val notification = buildNotification(Prefs.STATUS_LOADING, keyword, 0L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
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

        stopRecordingRequested = false
        running = true
        Prefs.setStatus(this, Prefs.STATUS_LOADING, keyword)
        worker = Thread({ loop(keyword) }, "escucha-loop").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        Prefs.setStatus(this, Prefs.STATUS_IDLE)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- bucle principal

    @SuppressLint("MissingPermission")
    private fun loop(keyword: String) {
        var model: Model? = null
        var detector: VoiceKeywordDetector? = null
        var audio: AudioRecord? = null
        var wav: WavWriter? = null
        try {
            require(KeywordPhrase(keyword).isValid) { "La palabra clave debe incluir letras" }
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val modelPath = prepareModel()
            if (!running) return
            model = Model(modelPath.absolutePath)
            detector = VoiceKeywordDetector(model, SAMPLE_RATE, keyword)
            if (!running) return

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBuf > 0) { "El micrófono no admite el formato de audio" }
            audio = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE)
            )
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "No se pudo abrir el micrófono" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "El micrófono no está grabando"
            }

            publishStatus(Prefs.STATUS_LISTENING, keyword)

            val buf = ShortArray(2048)
            val history = PcmHistory(SAMPLE_RATE * PRE_ROLL_SECONDS)
            var recStart = 0L

            while (running) {
                val n = audio.read(buf, 0, buf.size)
                if (!running) break
                check(n >= 0) { "No se pudo leer el micrófono ($n)" }
                if (n == 0) continue

                if (wav == null) {
                    history.append(buf, n)
                    if (detector.accept(buf, n) && running) {
                        detector.reset()
                        stopRecordingRequested = false
                        wav = WavWriter(newPendingRecordingFile(), SAMPLE_RATE)
                        history.writeTo(wav)
                        history.clear()
                        recStart = SystemClock.elapsedRealtime()
                        publishStatus(Prefs.STATUS_RECORDING, keyword, System.currentTimeMillis())
                    }
                } else {
                    wav.write(buf, n)
                    val elapsed = SystemClock.elapsedRealtime() - recStart
                    val stop = stopRecordingRequested || elapsed >= MAX_RECORDING_MS ||
                        detector.accept(buf, n)
                    if (stop) {
                        val completedRecording = wav
                        wav = null
                        completedRecording.close()
                        detector.reset()
                        stopRecordingRequested = false
                        publishStatus(Prefs.STATUS_LISTENING, keyword)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error en el bucle", e)
            toast("Error: ${e.message}")
        } finally {
            try {
                wav?.close()
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo guardar la grabación", e)
                toast("No se pudo guardar la grabación")
            }
            try { audio?.stop() } catch (_: Exception) {}
            try { audio?.release() } catch (_: Exception) {}
            try { detector?.close() } catch (_: Exception) {}
            try { model?.close() } catch (_: Exception) {}
            main.post {
                worker = null
                if (!destroyed) {
                    running = false
                    Prefs.setStatus(this, Prefs.STATUS_IDLE)
                    stopSelf()
                }
            }
        }
    }

    private fun newPendingRecordingFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File.createTempFile("rec_${stamp}_", ".part", recordingsDir(this))
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
            .setColor(ContextCompat.getColor(this, R.color.recorder_accent))
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

    private fun publishStatus(state: String, keyword: String, since: Long = 0L) {
        main.post {
            if (!running || destroyed) return@post
            Prefs.setStatus(this, state)
            updateNotification(state, keyword, since)
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
