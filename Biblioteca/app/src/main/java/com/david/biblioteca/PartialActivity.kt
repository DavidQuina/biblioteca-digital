package com.david.biblioteca

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivityPartialBinding
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class PartialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPartialBinding
    private lateinit var session: Session

    // Llegan desde el inicio del profesor
    private var subjectId = 0L
    private var subjectName = ""
    private var grade = 3
    private var group = "A"

    private var selectedDate: String? = null           // "2026-10-15" (formato del servidor)
    private val materialChecks = mutableMapOf<Long, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPartialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = Session(this)
        subjectId = intent.getLongExtra("SUBJECT_ID", 0)
        subjectName = intent.getStringExtra("SUBJECT_NAME") ?: ""
        grade = intent.getIntExtra("GRADE", 3)
        group = intent.getStringExtra("GROUP") ?: "A"
        binding.tvContext.text = "$subjectName $grade° $group"

        binding.btnBack.setOnClickListener { finish() }
        binding.cardDate.setOnClickListener { pickDate() }
        binding.btnSave.setOnClickListener { save() }

        loadMaterials()
        loadPartials()
    }

    /** Abre el calendario. */
    private fun pickDate() {
        val today = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                binding.tvDate.text = String.format(Locale.US, "📅  %02d/%02d/%04d", day, month + 1, year)
            },
            today.get(Calendar.YEAR),
            today.get(Calendar.MONTH),
            today.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** Trae el material de esta materia y grupo para marcar lo que vieron en la semana. */
    private fun loadMaterials() {
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.materials("Bearer ${session.token}", subjectId, grade, group)
                if (!response.isSuccessful) {
                    binding.tvNoMaterials.text = ApiClient.errorMessage(response)
                    return@launch
                }
                val list = response.body().orEmpty()
                binding.listMaterials.removeAllViews()
                materialChecks.clear()
                if (list.isEmpty()) {
                    binding.tvNoMaterials.text = "Aún no hay material en este grupo. Súbelo primero en \"Subir\"."
                    binding.tvNoMaterials.visibility = View.VISIBLE
                    return@launch
                }
                binding.tvNoMaterials.visibility = View.GONE
                for (m in list) {
                    val check = CheckBox(this@PartialActivity).apply {
                        text = "${iconFor(m.tipo)}  ${m.titulo}"
                        textSize = 16f
                        setPadding(8, 16, 8, 16)
                    }
                    materialChecks[m.id] = check
                    binding.listMaterials.addView(check)
                }
            } catch (e: Exception) {
                binding.tvNoMaterials.text = "Sin conexión con el servidor."
            }
        }
    }

    /** Lista de parciales ya programados. */
    private fun loadPartials() {
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.partials("Bearer ${session.token}", subjectId, grade, group)
                if (!response.isSuccessful) return@launch
                val list = response.body().orEmpty()
                binding.listPartials.removeAllViews()
                binding.tvNoPartials.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

                val density = resources.displayMetrics.density
                for (p in list) {
                    val text = StringBuilder("📅 ${p.titulo}\n${longDate(p.fecha)} · ${p.materiales.size} archivos")
                    if (p.aviso.isNotBlank()) text.append("\n“${p.aviso}”")

                    val item = TextView(this@PartialActivity).apply {
                        this.text = text.toString()
                        textSize = 16f
                        setTextColor(ContextCompat.getColor(context, R.color.ink))
                        setBackgroundColor(ContextCompat.getColor(context, android.R.color.white))
                        val pad = (14 * density).toInt()
                        setPadding(pad, pad, pad, pad)
                    }
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.topMargin = (8 * density).toInt()
                    binding.listPartials.addView(item, params)
                }
            } catch (e: Exception) {
                // Sin conexión: la lista queda vacía
            }
        }
    }

    private fun save() {
        val name = binding.etName.text.toString().trim()
        val notice = binding.etNotice.text.toString().trim()
        val date = selectedDate
        val error = when {
            name.isEmpty() -> "Escribe el nombre del parcial"
            date == null -> "Elige la fecha en el calendario"
            else -> null
        }
        if (error != null || date == null) {
            showError(error)
            return
        }
        showError(null)

        val selectedIds = materialChecks.filterValues { it.isChecked }.keys.toList()
        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.createPartial(
                    "Bearer ${session.token}",
                    CreatePartialRequest(name, date, notice, subjectId, grade, group, selectedIds)
                )
                if (response.isSuccessful) {
                    Toast.makeText(
                        this@PartialActivity,
                        "Parcial guardado: aparece en la Agenda del estudiante",
                        Toast.LENGTH_LONG
                    ).show()
                    clearForm()
                    loadPartials()
                } else {
                    showError(ApiClient.errorMessage(response))
                }
            } catch (e: Exception) {
                showError("Error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun clearForm() {
        binding.etName.setText("")
        binding.etNotice.setText("")
        selectedDate = null
        binding.tvDate.text = "📅  Elegir la fecha en el calendario"
        materialChecks.values.forEach { it.isChecked = false }
    }

    /** "2026-10-15" -> "15/10/2026" */
    private fun longDate(date: String): String {
        val parts = date.take(10).split("-")
        return if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else date
    }

    private fun iconFor(type: String) = when (type) {
        "pdf" -> "📄"
        "video" -> "🎬"
        "imagen" -> "🖼️"
        "examen" -> "📝"
        "formulario" -> "📋"
        "taller" -> "✏️"
        else -> "📁"
    }

    private fun setLoading(loading: Boolean) {
        binding.btnSave.isEnabled = !loading
        binding.btnSave.text = if (loading) "Guardando..." else "Guardar parcial"
    }

    private fun showError(message: String?) {
        binding.tvError.text = message
        binding.tvError.visibility = if (message == null) View.GONE else View.VISIBLE
    }
}