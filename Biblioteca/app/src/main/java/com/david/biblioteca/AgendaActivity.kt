package com.david.biblioteca

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.david.biblioteca.databinding.ActivityAgendaBinding
import com.david.biblioteca.databinding.ItemPartialBinding
import java.util.Calendar
import java.util.Locale

class AgendaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAgendaBinding
    private lateinit var store: OfflineStore
    private lateinit var data: SyncData

    private val month: Calendar = Calendar.getInstance()   // mes que se está viendo
    private var selectedDay: String? = null               // "2026-10-15" si tocó un día

    private val monthNames = listOf(
        "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
        "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgendaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        val user = Session(this).user ?: run { finish(); return }
        store = OfflineStore(this, user.id)
        data = store.loadSync() ?: run { finish(); return }

        // Empieza en el mes del próximo parcial (o en el mes actual)
        val today = dateKey(Calendar.getInstance())
        val next = data.parciales.map { it.fecha.take(10) }.filter { it >= today }.minOrNull()
        month.set(Calendar.DAY_OF_MONTH, 1)
        if (next != null) {
            val p = next.split("-")
            month.set(p[0].toInt(), p[1].toInt() - 1, 1)
        }

        binding.btnPrevMonth.setOnClickListener { month.add(Calendar.MONTH, -1); selectedDay = null; render() }
        binding.btnNextMonth.setOnClickListener { month.add(Calendar.MONTH, 1); selectedDay = null; render() }

        // Fila L M X J V S D
        listOf("L", "M", "X", "J", "V", "S", "D").forEach { letter ->
            val tv = TextView(this).apply {
                text = letter
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.gray))
                setTypeface(typeface, Typeface.BOLD)
            }
            binding.rowWeekDays.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        render()
    }

    private fun dateKey(c: Calendar) =
        String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        renderCalendar()
        renderList()
    }

    // 1 y 2. Calendario
    private fun renderCalendar() {
        binding.tvMonth.text = "${monthNames[month.get(Calendar.MONTH)]} ${month.get(Calendar.YEAR)}"
        binding.calendarGrid.removeAllViews()

        val partialDays = data.parciales.map { it.fecha.take(10) }.toSet()
        val today = dateKey(Calendar.getInstance())

        val first = month.clone() as Calendar
        first.set(Calendar.DAY_OF_MONTH, 1)
        val emptyCells = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7   // lunes primero
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)

        val cells: List<Int?> = List(emptyCells) { null } + (1..daysInMonth).toList()
        cells.chunked(7).forEach { week ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 7) {
                val cell = FrameLayout(this)
                row.addView(cell, LinearLayout.LayoutParams(0, dp(46), 1f))
                val day = week.getOrNull(i) ?: continue

                val c = first.clone() as Calendar
                c.set(Calendar.DAY_OF_MONTH, day)
                val key = dateKey(c)
                val marked = key in partialDays

                val tv = TextView(this).apply {
                    text = day.toString()
                    gravity = Gravity.CENTER
                    textSize = 16f
                    when {
                        marked -> {
                            setBackgroundResource(R.drawable.bg_day_marked)
                            setTextColor(Color.WHITE)
                            setTypeface(typeface, Typeface.BOLD)
                        }
                        key == today -> {
                            setBackgroundResource(R.drawable.bg_day_today)
                            setTextColor(ContextCompat.getColor(context, R.color.ink))
                        }
                        else -> setTextColor(ContextCompat.getColor(context, R.color.ink))
                    }
                    if (key == selectedDay) alpha = 0.6f
                    setOnClickListener {
                        selectedDay = if (selectedDay == key) null else key
                        render()
                    }
                }
                cell.addView(tv, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            }
            binding.calendarGrid.addView(row)
        }
    }

    //Lista de parciales
    private fun renderList() {
        binding.listPartials.removeAllViews()
        val monthPrefix = String.format(Locale.US, "%04d-%02d", month.get(Calendar.YEAR), month.get(Calendar.MONTH) + 1)
        val list = data.parciales
            .filter { p -> selectedDay?.let { p.fecha.startsWith(it) } ?: p.fecha.startsWith(monthPrefix) }
            .sortedBy { it.fecha }

        binding.tvListTitle.text = if (selectedDay != null) "Parciales del ${longDate(selectedDay!!)}" else "Parciales del mes"
        binding.tvEmpty.text = if (selectedDay != null) "🗓️\nNo hay parciales ese día." else "🗓️\nNo hay parciales este mes."
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        list.forEach { p ->
            val subject = data.materias.firstOrNull { it.id == p.subjectId }
            val card = ItemPartialBinding.inflate(layoutInflater, binding.listPartials, false)
            card.tvIcon.text = subject?.icono ?: "📅"
            card.tvTitle.text = p.titulo
            card.tvDate.text = "${longDate(p.fecha)} · ${subject?.nombre ?: ""}"
            card.tvMaterials.text = "Material para estudiar: ${p.materiales.size} archivos"
            card.btnSeeMaterial.visibility = if (p.materiales.isEmpty()) View.GONE else View.VISIBLE
            card.btnSeeMaterial.setOnClickListener { showMaterials(p) }
            card.layoutNotice.visibility = if (p.aviso.isBlank()) View.GONE else View.VISIBLE
            card.tvNotice.text = p.aviso
            binding.listPartials.addView(card.root)
        }
    }

    // 3. Ver material
    private fun showMaterials(p: SyncPartial) {
        val names = p.materiales.map { m ->
            val ready = store.isDownloaded(m.id)
            "${iconForType(m.tipo)}  ${m.titulo}" + if (ready) "  ✅" else "  (sin descargar)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Para estudiar: ${p.titulo}")
            .setItems(names) { _, which ->
                val m = p.materiales[which]
                if (store.isDownloaded(m.id)) {
                    val classNumber = data.materiales.filter { it.subjectId == p.subjectId }
                        .indexOfFirst { it.id == m.id } + 1
                    startActivity(
                        Intent(this, ViewerActivity::class.java)
                            .putExtra("MATERIAL_ID", m.id)
                            .putExtra("CLASS_NUMBER", if (classNumber > 0) classNumber else 1)
                    )
                } else {
                    val subject = data.materias.firstOrNull { it.id == p.subjectId }?.nombre ?: "la materia"
                    Toast.makeText(this, "Descárgalo en $subject (con internet) para verlo sin conexión", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** "2026-10-15" -> "15/10/2026" */
    private fun longDate(date: String): String {
        val parts = date.take(10).split("-")
        return if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else date
    }
}