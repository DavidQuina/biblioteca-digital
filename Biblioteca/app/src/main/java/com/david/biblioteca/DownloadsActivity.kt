package com.david.biblioteca

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivityDownloadsBinding
import com.david.biblioteca.databinding.ItemDownloadBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var session: Session

    private var subjectId = 0L
    private var subjectName = ""
    private var grade = 3
    private var group = "A"
    private var onlySubject = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = Session(this)
        subjectId = intent.getLongExtra("SUBJECT_ID", 0)
        subjectName = intent.getStringExtra("SUBJECT_NAME") ?: ""
        grade = intent.getIntExtra("GRADE", 3)
        group = intent.getStringExtra("GROUP") ?: "A"

        binding.btnBack.setOnClickListener { finish() }
        binding.tvGroup.text = "Grupo: $grade° $group"
        binding.btnSubject.text = subjectName

        // 1. Filtro: todas las materias o solo la elegida
        binding.toggleFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                onlySubject = checkedId == R.id.btnSubject
                load()
            }
        }

        load()
    }

    private fun load() {
        binding.tvMessage.text = "Cargando..."
        binding.tvMessage.visibility = View.VISIBLE
        binding.listRows.removeAllViews()
        binding.tvSummary.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val response = ApiClient.api.downloadsReport(
                    "Bearer ${session.token}", grade, group, if (onlySubject) subjectId else null
                )
                if (!response.isSuccessful) {
                    binding.tvMessage.text = ApiClient.errorMessage(response)
                    return@launch
                }
                show(response.body()!!)
            } catch (e: Exception) {
                binding.tvMessage.text = "Sin conexión con el servidor."
            }
        }
    }

    private fun show(report: DownloadReport) {
        if (report.estudiantes == 0) {
            binding.tvMessage.text = "👧\nTodavía no hay estudiantes registrados en $grade° $group."
            return
        }
        binding.tvMessage.visibility = View.GONE

        // 2 y 3. Qué bajó cada estudiante, fecha y estado
        report.filas.forEach { row ->
            val item = ItemDownloadBinding.inflate(layoutInflater, binding.listRows, false)
            item.tvName.text = row.nombre
            item.tvResource.text = row.titulo ?: "Aún no descarga"
            item.tvDate.text = row.fecha?.let { shortDate(it) } ?: "--"

            when (row.estado) {
                "activa" -> chip(item, "Activa", R.color.green_soft, R.color.green)
                "quitada" -> chip(item, "Quitada", null, R.color.red)
                else -> chip(item, "Pendiente", R.color.yellow_soft, null)
            }
            binding.listRows.addView(item.root)
        }

        // 4. Resumen: así controla quién falta
        binding.tvSummary.visibility = View.VISIBLE
        binding.tvSummary.text = "  ${report.downloaded} de ${report.estudiantes} estudiantes ya descargaron"
    }

    /** Etiqueta de estado con su color. */
    private fun chip(item: ItemDownloadBinding, text: String, backgroundRes: Int?, textRes: Int?) {
        item.tvState.text = text
        item.tvState.setBackgroundColor(
            if (backgroundRes != null) ContextCompat.getColor(this, backgroundRes) else Color.parseColor("#FFE5E5")
        )
        item.tvState.setTextColor(
            if (textRes != null) ContextCompat.getColor(this, textRes) else Color.parseColor("#B7791F")
        )
    }

    /** "2026-10-12 15:30:00" (UTC) -> "Hoy", "Ayer" o "12/10" */
    private fun shortDate(text: String): String {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            val date = format.parse(text) ?: return "--"
            val day = Calendar.getInstance().apply { time = date }
            val today = Calendar.getInstance()
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            fun same(a: Calendar, b: Calendar) =
                a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            when {
                same(day, today) -> "Hoy"
                same(day, yesterday) -> "Ayer"
                else -> String.format(Locale.US, "%02d/%02d", day.get(Calendar.DAY_OF_MONTH), day.get(Calendar.MONTH) + 1)
            }
        } catch (e: Exception) {
            "--"
        }
    }
}