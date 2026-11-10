package com.example.piadismov
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etWord: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: TextView
    private lateinit var btnStopRec: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var list: ListView
    private lateinit var adapter: RecordingAdapter

    private val files = mutableListOf<File>()
    private var player: MediaPlayer? = null
    private var playing: File? = null
    private val dateFmt = SimpleDateFormat("d 'de' MMM, HH:mm", Locale("es", "MX"))

    // Se dispara cuando el servicio cambia el estado.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refreshUi() }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMic()) startListening()
            else toast("Sin permiso de micrófono no puedo escuchar")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etWord = findViewById(R.id.et_keyword)
        tvStatus = findViewById(R.id.tv_status)
        btnToggle = findViewById(R.id.btn_toggle)
        btnStopRec = findViewById(R.id.btn_stop_rec)
        tvEmpty = findViewById(R.id.tv_empty)
        list = findViewById(R.id.lv_recordings)

        etWord.setText(Prefs.getKeyword(this))

        adapter = RecordingAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> togglePlay(files[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(files[pos]); true }

        // La palabra se guarda al pulsar "Listo" en el teclado.
        etWord.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (saveKeyword()) {
                    hideKeyboard()
                    if (Prefs.getStatus(this) != Prefs.STATUS_IDLE) {
                        toast("Guardada. Apaga y activa la escucha para aplicarla")
                    }
                }
                true
            } else false
        }

        btnToggle.setOnClickListener {
            if (Prefs.getStatus(this) == Prefs.STATUS_IDLE) {
                if (saveKeyword()) {
                    hideKeyboard()
                    requestPermsAndStart()
                }
            } else {
                stopListening()
            }
        }

        btnStopRec.setOnClickListener {
            startService(
                Intent(this, ListenService::class.java).setAction(ListenService.ACTION_STOP_RECORDING)
            )
        }
    }

    override fun onStart() {
        super.onStart()
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        refreshUi()
    }

    override fun onStop() {
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        stopPlayback()
        super.onStop()
    }

    // ------------------------------------------------------------------ acciones

    private fun saveKeyword(): Boolean {
        val word = etWord.text.toString().trim().lowercase(Locale.ROOT)
        if (word.isEmpty()) {
            toast("Escribe una palabra clave")
            return false
        }
        if (word != etWord.text.toString()) etWord.setText(word)
        Prefs.setKeyword(this, word)
        WordWidget.refresh(this)
        return true
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(etWord.windowToken, 0)
        etWord.clearFocus()
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermsAndStart() {
        val needed = mutableListOf<String>()
        if (!hasMic()) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) startListening() else permLauncher.launch(needed.toTypedArray())
    }

    private fun startListening() {
        ContextCompat.startForegroundService(
            this, Intent(this, ListenService::class.java).setAction(ListenService.ACTION_START)
        )
    }

    private fun stopListening() {
        startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_STOP))
    }

    // ------------------------------------------------------------------ reproducción

    private fun togglePlay(file: File) {
        val wasPlaying = playing == file
        stopPlayback()
        if (wasPlaying) return
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playing = file
        } catch (e: Exception) {
            stopPlayback()
            toast("No se pudo reproducir")
        }
        adapter.notifyDataSetChanged()
    }

    private fun stopPlayback() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        playing = null
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Eliminar grabación?")
            .setMessage(dateFmt.format(Date(file.lastModified())))
            .setPositiveButton("Eliminar") { _, _ ->
                if (playing == file) stopPlayback()
                file.delete()
                refreshUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ interfaz

    private fun refreshUi() {
        val status = Prefs.getStatus(this)
        val accent = ContextCompat.getColor(this, R.color.pc_accent)
        val muted = ContextCompat.getColor(this, R.color.pc_text_secondary)
        val text = ContextCompat.getColor(this, R.color.pc_text)

        when (status) {
            Prefs.STATUS_LOADING -> { tvStatus.text = "CARGANDO MODELO…"; tvStatus.setTextColor(muted) }
            Prefs.STATUS_LISTENING -> { tvStatus.text = "ESCUCHANDO"; tvStatus.setTextColor(accent) }
            Prefs.STATUS_RECORDING -> { tvStatus.text = "● GRABANDO"; tvStatus.setTextColor(accent) }
            else -> { tvStatus.text = "APAGADO"; tvStatus.setTextColor(muted) }
        }

        val idle = status == Prefs.STATUS_IDLE
        val recording = status == Prefs.STATUS_RECORDING

        btnToggle.text = if (idle) "Activar escucha" else "Apagar"
        btnToggle.setBackgroundResource(
            if (idle) R.drawable.bg_primary_button else R.drawable.bg_secondary_button
        )
        btnToggle.setTextColor(if (idle) 0xFFFFFFFF.toInt() else text)
        btnStopRec.visibility = if (recording) View.VISIBLE else View.GONE

        var found = ListenService.recordingsDir(this)
            .listFiles { f -> f.extension == "wav" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        // La grabación en curso todavía no tiene el encabezado completo: no se muestra.
        if (recording) found = found.drop(1)

        files.clear()
        files.addAll(found)
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun duration(f: File): String {
        val secs = ((f.length() - 44).coerceAtLeast(0) / 32000).toInt()
        return "%d:%02d".format(secs / 60, secs % 60)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private inner class RecordingAdapter : BaseAdapter() {
        override fun getCount() = files.size
        override fun getItem(position: Int): Any = files[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
            val f = files[position]
            v.findViewById<TextView>(R.id.item_title).text = dateFmt.format(Date(f.lastModified()))
            v.findViewById<TextView>(R.id.item_sub).text = duration(f)
            v.findViewById<ImageView>(R.id.item_icon).setImageResource(
                if (f == playing) R.drawable.ic_pause else R.drawable.ic_play
            )
            return v
        }
    }
}
