package com.example.piadismov

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etWord: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var statusDot: View
    private lateinit var btnToggle: Button
    private lateinit var btnStopRec: Button
    private lateinit var emptyRecordings: View
    private lateinit var recordingsCount: TextView
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
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val screen = findViewById<View>(R.id.recorder_screen)
        ViewCompat.setOnApplyWindowInsetsListener(screen) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(screen)

        val list = findViewById<ListView>(R.id.lv_recordings)
        val header = layoutInflater.inflate(R.layout.recorder_header, list, false)
        list.setItemsCanFocus(true)
        list.addHeaderView(header, null, false)

        etWord = header.findViewById(R.id.et_keyword)
        tvStatus = header.findViewById(R.id.tv_status)
        tvStatusTitle = header.findViewById(R.id.tv_status_title)
        tvStatusDetail = header.findViewById(R.id.tv_status_detail)
        statusDot = header.findViewById(R.id.status_dot)
        btnToggle = header.findViewById(R.id.btn_toggle)
        btnStopRec = header.findViewById(R.id.btn_stop_rec)
        emptyRecordings = header.findViewById(R.id.empty_recordings)
        recordingsCount = header.findViewById(R.id.tv_recordings_count)

        etWord.setText(Prefs.getKeyword(this))

        adapter = RecordingAdapter()
        list.adapter = adapter
        screen.requestFocus()

        header.findViewById<Button>(R.id.btn_save_word).setOnClickListener { saveKeywordFromEditor() }
        etWord.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveKeywordFromEditor()
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

    private fun saveKeywordFromEditor() {
        if (!saveKeyword()) return
        hideKeyboard()
        val message = if (Prefs.getStatus(this) == Prefs.STATUS_IDLE) {
            R.string.recorder_keyword_saved
        } else {
            R.string.recorder_keyword_reactivate
        }
        toast(getString(message))
    }

    private fun saveKeyword(): Boolean {
        val word = etWord.text.toString().trim().lowercase(Locale.ROOT)
        if (!KeywordPhrase(word).isValid) {
            etWord.requestFocus()
            etWord.error = getString(R.string.recorder_keyword_required)
            return false
        }
        etWord.error = null
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
            val nextPlayer = MediaPlayer()
            player = nextPlayer
            playing = file
            nextPlayer.setDataSource(file.absolutePath)
            nextPlayer.setOnPreparedListener { prepared ->
                if (player === prepared) {
                    try {
                        prepared.start()
                    } catch (_: IllegalStateException) {
                        handlePlaybackError()
                    }
                }
            }
            nextPlayer.setOnCompletionListener { completed ->
                if (player === completed) stopPlayback()
            }
            nextPlayer.setOnErrorListener { failed, _, _ ->
                if (player === failed) handlePlaybackError()
                true
            }
            nextPlayer.prepareAsync()
            adapter.notifyDataSetChanged()
        } catch (_: Exception) {
            handlePlaybackError()
        }
    }

    private fun handlePlaybackError() {
        stopPlayback()
        toast("No se pudo reproducir la grabación")
    }

    private fun stopPlayback() {
        val previousPlayer = player
        player = null
        playing = null
        previousPlayer?.release()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Eliminar grabación?")
            .setMessage(dateFmt.format(Date(file.lastModified())))
            .setPositiveButton("Eliminar") { _, _ ->
                if (playing == file) stopPlayback()
                if (!file.delete() && file.exists()) toast("No se pudo eliminar la grabación")
                refreshUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ interfaz

    private fun refreshUi() {
        val status = when (Prefs.getStatus(this)) {
            Prefs.STATUS_LOADING -> RecorderStatus.Loading
            Prefs.STATUS_LISTENING -> RecorderStatus.Listening
            Prefs.STATUS_RECORDING -> RecorderStatus.Recording
            else -> RecorderStatus.Idle
        }

        val idle = status == RecorderStatus.Idle
        tvStatus.setText(status.label)
        tvStatusTitle.setText(status.title)
        tvStatusDetail.setText(status.detail)
        statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, status.dot))
        btnToggle.setText(if (idle) R.string.recorder_enable else R.string.recorder_disable)
        btnToggle.setBackgroundResource(
            if (idle) R.drawable.bg_recorder_primary else R.drawable.bg_recorder_secondary
        )
        btnToggle.setTextColor(
            ContextCompat.getColor(
                this, if (idle) R.color.recorder_on_primary else R.color.recorder_hero_text
            )
        )
        btnStopRec.visibility = if (status == RecorderStatus.Recording) View.VISIBLE else View.GONE

        val found = ListenService.recordingsDir(this)
            .listFiles { f -> f.isFile && f.extension == "wav" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        files.clear()
        files.addAll(found)
        emptyRecordings.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        recordingsCount.text = resources.getQuantityString(
            R.plurals.recorder_audio_count, files.size, files.size
        )
        adapter.notifyDataSetChanged()
    }

    private enum class RecorderStatus(val label: Int, val title: Int, val detail: Int, val dot: Int) {
        Idle(
            R.string.recorder_status_idle, R.string.recorder_heading_idle,
            R.string.recorder_detail_idle, R.color.recorder_dot_idle
        ),
        Loading(
            R.string.recorder_status_loading, R.string.recorder_heading_loading,
            R.string.recorder_detail_loading, R.color.recorder_dot_loading
        ),
        Listening(
            R.string.recorder_status_listening, R.string.recorder_heading_listening,
            R.string.recorder_detail_listening, R.color.recorder_dot_listening
        ),
        Recording(
            R.string.recorder_status_recording, R.string.recorder_heading_recording,
            R.string.recorder_detail_recording, R.color.recorder_dot_recording
        )
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
            val date = dateFmt.format(Date(f.lastModified()))
            val length = duration(f)
            val isSelected = f == playing
            v.findViewById<TextView>(R.id.item_title).text = date
            v.findViewById<TextView>(R.id.item_sub).text = length
            v.findViewById<View>(R.id.recording_card).isActivated = isSelected
            v.findViewById<ImageView>(R.id.item_icon).setImageResource(
                if (isSelected) R.drawable.ic_stop else R.drawable.ic_play
            )
            v.findViewById<View>(R.id.recording_play).apply {
                contentDescription = getString(
                    if (isSelected) R.string.stop_playback else R.string.play_recording, date, length
                )
                setOnClickListener { togglePlay(f) }
                setOnLongClickListener {
                    confirmDelete(f)
                    true
                }
            }
            v.findViewById<View>(R.id.item_delete).apply {
                contentDescription = getString(R.string.recorder_delete_action, date)
                setOnClickListener { confirmDelete(f) }
            }
            return v
        }
    }
}
