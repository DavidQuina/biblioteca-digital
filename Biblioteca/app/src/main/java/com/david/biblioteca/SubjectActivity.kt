package com.david.biblioteca

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivitySubjectBinding
import com.david.biblioteca.databinding.ItemClassBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SubjectActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubjectBinding
    private lateinit var session: Session
    private lateinit var store: OfflineStore

    private var subjectId = 0L
    private var subjectColor = Color.parseColor("#FF6B4A")
    private var section = "clases"
    private var seenAtEntry: Set<String> = emptySet()
    private val downloading = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubjectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = Session(this)
        val user = session.user ?: run { finish(); return }
        store = OfflineStore(this, user.id)
        subjectId = intent.getLongExtra("SUBJECT_ID", 0)

        val data = store.loadSync() ?: run { finish(); return }
        val subject = data.materias.firstOrNull { it.id == subjectId } ?: run { finish(); return }

        // Encabezado con el color de la materia
        subjectColor = try { Color.parseColor(subject.color) } catch (e: Exception) { subjectColor }
        binding.header.setBackgroundColor(subjectColor)
        binding.tvSubject.text = "${subject.icono} ${subject.nombre}"
        binding.btnBack.setOnClickListener { finish() }

        // Lo "nuevo" se calcula con lo visto al entrar; luego todo queda como visto
        seenAtEntry = store.seen()
        store.markSeen(
            data.materiales.filter { it.subjectId == subjectId }.map { OfflineStore.materialKey(it) } +
                    data.pruebas.filter { it.subjectId == subjectId }.map { OfflineStore.testKey(it) } +
                    data.juegos.filter { it.subjectId == subjectId }.map { OfflineStore.gameKey(it) }
        )

        binding.toggleSection.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            section = when (checkedId) {
                R.id.btnTests -> "pruebas"
                R.id.btnGames -> "juegos"
                else -> "clases"
            }
            render()
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) render()
    }

    private fun render() {
        val data = store.loadSync() ?: return
        binding.listItems.removeAllViews()
        binding.tvCounter.visibility = if (section == "clases") View.VISIBLE else View.GONE
        updateCounter()
        when (section) {
            "clases" -> renderClasses(data)
            "pruebas" -> renderTests(data)
            "juegos" -> renderGames(data)
        }
    }

    private fun updateCounter() {
        val count = store.downloadedCount()
        val full = count >= OfflineStore.MAX_DOWNLOADS
        binding.tvCounter.text =
            "$count de ${OfflineStore.MAX_DOWNLOADS} descargas" + if (full) "\nQuita una para descargar otra" else ""
        binding.tvCounter.setTextColor(ContextCompat.getColor(this, if (full) R.color.red else R.color.ink))
    }

    private fun showEmpty(text: String?) {
        binding.tvEmpty.text = text
        binding.tvEmpty.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    //  Clases
    private fun renderClasses(data: SyncData) {
        val materials = data.materiales.filter { it.subjectId == subjectId }
        showEmpty(if (materials.isEmpty()) "📭\nTu profe aún no ha subido clases en esta materia." else null)

        materials.forEachIndexed { index, m ->
            val card = ItemClassBinding.inflate(layoutInflater, binding.listItems, false)
            card.tvIcon.text = iconForType(m.tipo)
            card.tvTitle.text = "Clase ${index + 1}: ${m.titulo}"
            card.tvInfo.text = "${typeName(m.tipo)}, ${readableSize(m.tamano)}"
            card.tvNew.visibility = if (OfflineStore.materialKey(m) !in seenAtEntry) View.VISIBLE else View.GONE

            when {
                // 3. Ya lo descargó: Abrir (funciona sin internet)
                store.isDownloaded(m.id) -> {
                    setButton(card, "Abrir", R.color.green)
                    card.btnAction.setOnClickListener {
                        val intent = android.content.Intent(this, ViewerActivity::class.java)
                        intent.putExtra("MATERIAL_ID", m.id)
                        intent.putExtra("CLASS_NUMBER", index + 1)
                        startActivity(intent)
                    }
                    card.btnRemove.visibility = View.VISIBLE
                    card.btnRemove.setOnClickListener { askRemove(m) }
                }
                // Descargando...
                m.id in downloading -> {
                    setButton(card, "Bajando...", R.color.gray)
                    card.btnAction.isEnabled = false
                }
                // 4. No lo tiene: Descargar (con internet, una sola vez)
                else -> {
                    setButton(card, "Descargar", if (isOnline(this)) R.color.blue else R.color.gray)
                    card.btnAction.setOnClickListener { download(m) }
                }
            }
            binding.listItems.addView(card.root)
        }
    }

    private fun setButton(card: ItemClassBinding, text: String, colorRes: Int) {
        card.btnAction.text = text
        card.btnAction.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun download(m: SyncMaterial) {
        if (!isOnline(this)) {
            Toast.makeText(this, "Necesitas internet para descargar (una sola vez)", Toast.LENGTH_LONG).show()
            return
        }
        if (store.downloadedCount() >= OfflineStore.MAX_DOWNLOADS) {
            Toast.makeText(this, "Llegaste al máximo de 10 descargas. Quita una para descargar otra.", Toast.LENGTH_LONG).show()
            return
        }
        downloading.add(m.id)
        render()

        lifecycleScope.launch {
            try {
                val response = ApiClient.api.download("Bearer ${session.token}", m.id)
                if (!response.isSuccessful) {
                    Toast.makeText(this@SubjectActivity, ApiClient.errorMessage(response), Toast.LENGTH_LONG).show()
                    return@launch
                }
                val version = response.headers()["X-Version"]?.toIntOrNull() ?: m.version
                // Guardar el archivo en el celular (primero en .tmp para no dejar archivos a medias)
                withContext(Dispatchers.IO) {
                    val destination = store.materialFile(m.id)
                    val temp = File(destination.parentFile, "${m.id}.tmp")
                    response.body()!!.byteStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    temp.renameTo(destination)
                }
                store.saveVersion(m.id, version)
                Toast.makeText(this@SubjectActivity, "¡Listo! Ya lo puedes abrir sin internet", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@SubjectActivity, "No se pudo descargar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                downloading.remove(m.id)
                render()
            }
        }
    }

    private fun askRemove(m: SyncMaterial) {
        AlertDialog.Builder(this)
            .setTitle("¿Quitar \"${m.titulo}\"?")
            .setMessage("Se borra del celular y liberas un cupo de descarga. Para volver a verlo tendrás que descargarlo con internet.")
            .setPositiveButton("Quitar") { _, _ -> remove(m) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun remove(m: SyncMaterial) {
        lifecycleScope.launch {
            if (isOnline(this@SubjectActivity)) {
                try {
                    ApiClient.api.removeDownload("Bearer ${session.token}", m.id)
                } catch (e: Exception) {
                    store.addPendingRemoved(m.id)   // se envía al actualizar
                }
            } else {
                store.addPendingRemoved(m.id)       // sin internet: se envía al actualizar
            }
            store.deleteMaterial(m.id)
            Toast.makeText(this@SubjectActivity, "Quitado. Ahora tienes un cupo libre.", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    //  Pruebas
    private fun renderTests(data: SyncData) {
        val tests = data.pruebas.filter { it.subjectId == subjectId }
        showEmpty(if (tests.isEmpty()) "📝\nNo hay pruebas en esta materia." else null)

        val pendingIds = store.pendingResults().map { it.testId }.toSet()
        tests.forEach { t ->
            val card = ItemClassBinding.inflate(layoutInflater, binding.listItems, false)
            card.tvIcon.text = "📝"
            card.tvTitle.text = t.titulo

            // La nota del servidor, o la que hizo en el celular (aunque aún no se haya enviado)
            val result = t.myResult ?: store.localResult(t.id)
            card.tvInfo.text = "${t.preguntas.size} preguntas" +
                    (result?.let { " · Tu nota: ${it.correctas}/${it.total}" } ?: "") +
                    (if (t.id in pendingIds) " · por enviar" else "")
            card.tvNew.visibility = if (OfflineStore.testKey(t) !in seenAtEntry) View.VISIBLE else View.GONE

            setButton(card, if (result == null) "Responder" else "Ver", R.color.purple)
            card.btnAction.setOnClickListener {
                val intent = android.content.Intent(this, TestActivity::class.java)
                intent.putExtra("TEST_ID", t.id)
                startActivity(intent)
            }
            binding.listItems.addView(card.root)
        }
    }

    // Juegos
    private fun renderGames(data: SyncData) {
        val games = data.juegos.filter { it.subjectId == subjectId }
        showEmpty(if (games.isEmpty()) "🎮\nTodavía no hay juegos en esta materia." else null)

        games.forEach { g ->
            val card = ItemClassBinding.inflate(layoutInflater, binding.listItems, false)
            card.tvIcon.text = when (g.tipo) { "memoria" -> "🃏"; "reloj" -> "⏱️"; else -> "🔗" }
            card.tvTitle.text = g.titulo
            card.tvInfo.text = if (g.byTeacher) "Hecho por tu profe" else "Juego de la app"
            card.tvNew.visibility = if (g.byTeacher && OfflineStore.gameKey(g) !in seenAtEntry) View.VISIBLE else View.GONE
            setButton(card, "Jugar", R.color.coral)
            card.btnAction.setOnClickListener {
                startActivity(android.content.Intent(this, GameActivity::class.java).putExtra("GAME_ID", g.id))
            }
            binding.listItems.addView(card.root)
        }
    }
}