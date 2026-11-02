package com.david.biblioteca

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivityTeacherHomeBinding
import com.david.biblioteca.databinding.ItemRecentBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class TeacherHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTeacherHomeBinding
    private lateinit var session: Session

    private var subjects: List<Subject> = emptyList()
    private var selectedSubject: Subject? = null
    private var grade = 3
    private var group = "A"

    private val grades = (1..11).map { "$it°" }
    private val groups = listOf("A", "B", "C", "D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeacherHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = Session(this)
        val user = session.user
        if (user == null) {
            goToStart()
            return
        }
        binding.tvEmail.text = user.correo

        binding.btnLogout.setOnClickListener {
            session.clear()
            goToStart()
        }

        // Grado y grupo
        binding.acGrade.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, grades))
        binding.acGroup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, groups))
        binding.acGrade.setText(grades[grade - 1], false)
        binding.acGroup.setText(group, false)
        binding.acGrade.setOnItemClickListener { _, _, position, _ ->
            grade = position + 1
            loadSummary()
        }
        binding.acGroup.setOnItemClickListener { _, _, position, _ ->
            group = groups[position]
            loadSummary()
        }

        // Subir material
        binding.btnUpload.setOnClickListener { openSection(UploadActivity::class.java) }

        // Crear prueba
        binding.btnTests.setOnClickListener { openSection(CreateTestActivity::class.java) }

        // Programar parciales
        binding.btnPartials.setOnClickListener { openSection(PartialActivity::class.java) }

        // Mi material: visible/oculto, actualizar y borrar
        binding.btnMaterial.setOnClickListener { openSection(MaterialActivity::class.java) }
        // Quién descargó qué
        binding.btnDownloads.setOnClickListener { openSection(DownloadsActivity::class.java) }

        // Resultados de las pruebas
        binding.btnGrades.setOnClickListener { openSection(ResultsActivity::class.java) }

        // Crear juego con su contenido
        binding.btnGames.setOnClickListener { openSection(CreateGameActivity::class.java) }
        loadSubjects()
    }

    override fun onResume() {
        super.onResume()
        // Al volver de otra pantalla (ej: después de subir algo) se recarga el resumen
        if (selectedSubject != null) loadSummary()
    }

    /** Trae la lista de materias del servidor. */
    private fun loadSubjects() {
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.subjects()
                if (response.isSuccessful) {
                    subjects = response.body().orEmpty()
                    val names = subjects.map { "${it.icono} ${it.nombre}" }
                    binding.acSubject.setAdapter(ArrayAdapter(this@TeacherHomeActivity, android.R.layout.simple_list_item_1, names))
                    binding.acSubject.setOnItemClickListener { _, _, position, _ ->
                        selectedSubject = subjects[position]
                        loadSummary()
                    }
                    if (subjects.isNotEmpty()) {
                        selectedSubject = subjects[0]
                        binding.acSubject.setText(names[0], false)
                        loadSummary()
                    }
                } else {
                    showError(ApiClient.errorMessage(response))
                }
            } catch (e: Exception) {
                showError("Sin conexión con el servidor. Conéctate para publicar.")
            }
        }
    }

    /** Trae los contadores y lo último publicado para la materia, grado y grupo elegidos. */
    private fun loadSummary() {
        val subject = selectedSubject ?: return
        binding.tvSummaryTitle.text = "${subject.nombre}, $grade° $group"
        showError(null)

        lifecycleScope.launch {
            try {
                val response = ApiClient.api.summary("Bearer ${session.token}", subject.id, grade, group)
                if (response.code() == 401) {
                    Toast.makeText(this@TeacherHomeActivity, "Tu sesión venció. Ingresa de nuevo.", Toast.LENGTH_LONG).show()
                    session.clear()
                    goToStart()
                    return@launch
                }
                if (!response.isSuccessful) {
                    showError(ApiClient.errorMessage(response))
                    return@launch
                }
                showSummary(response.body()!!)
            } catch (e: Exception) {
                showError("Sin conexión con el servidor. Conéctate para publicar.")
            }
        }
    }

    private fun showSummary(s: Summary) {
        binding.tvCountMaterials.text = "${s.materiales}\nmateriales"
        binding.tvCountTests.text = "${s.pruebas}\npruebas"
        binding.tvCountPartials.text = "${s.parciales}\nparciales"
        binding.tvSummaryNote.text = "Todo lo que subas queda aquí · ${s.estudiantes} estudiantes en el grupo"

        binding.listRecent.removeAllViews()
        binding.tvEmpty.visibility = if (s.ultimos.isEmpty()) View.VISIBLE else View.GONE

        for (item in s.ultimos) {
            val row = ItemRecentBinding.inflate(layoutInflater, binding.listRecent, false)
            row.tvIcon.text = iconFor(item)
            row.tvTitle.text = item.titulo
            row.tvDate.text = relativeDate(item.creadoEn)
            binding.listRecent.addView(row.root)
        }
    }

    private fun iconFor(item: Recent): String = when (item.clase) {
        "prueba" -> "📝"
        "parcial" -> "📅"
        "juego" -> "🎮"
        else -> when (item.tipo) {
            "pdf" -> "📄"
            "video" -> "🎬"
            "imagen" -> "🖼️"
            "examen" -> "📝"
            "formulario" -> "📋"
            "taller" -> "✏️"
            else -> "📁"
        }
    }

    /** El servidor manda la fecha en UTC ("2026-10-13 20:02:45"). Devuelve "Hoy", "Ayer" o "12/10". */
    private fun relativeDate(text: String): String {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            val date = format.parse(text) ?: return "--"
            val day = Calendar.getInstance().apply { time = date }
            val today = Calendar.getInstance()
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            fun sameDay(a: Calendar, b: Calendar) =
                a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            when {
                sameDay(day, today) -> "Hoy"
                sameDay(day, yesterday) -> "Ayer"
                else -> String.format(Locale.US, "%02d/%02d", day.get(Calendar.DAY_OF_MONTH), day.get(Calendar.MONTH) + 1)
            }
        } catch (e: Exception) {
            "--"
        }
    }

    /** Abre una pantalla del profesor enviándole la materia, grado y grupo elegidos. */
    private fun openSection(screen: Class<*>) {
        val subject = selectedSubject
        if (subject == null) {
            Toast.makeText(this, "Espera a que carguen las materias (necesitas internet)", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, screen)
        intent.putExtra("SUBJECT_ID", subject.id)
        intent.putExtra("SUBJECT_NAME", subject.nombre)
        intent.putExtra("GRADE", grade)
        intent.putExtra("GROUP", group)
        startActivity(intent)
    }

    private fun showError(message: String?) {
        binding.tvError.text = message
        binding.tvError.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    /** Vuelve a la pantalla de elegir rol y borra el historial. */
    private fun goToStart() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}