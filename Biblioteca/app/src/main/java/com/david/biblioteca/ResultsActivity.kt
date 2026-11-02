package com.david.biblioteca

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivityResultsBinding
import kotlinx.coroutines.launch
import java.util.Locale

class ResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultsBinding
    private lateinit var session: Session

    private var subjectId = 0L
    private var subjectName = ""
    private var grade = 3
    private var group = "A"

    private var tests: List<TestSummary> = emptyList()
    private var selectedTest: TestSummary? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = Session(this)
        subjectId = intent.getLongExtra("SUBJECT_ID", 0)
        subjectName = intent.getStringExtra("SUBJECT_NAME") ?: ""
        grade = intent.getIntExtra("GRADE", 3)
        group = intent.getStringExtra("GROUP") ?: "A"

        binding.tvContext.text = "$subjectName $grade° $group"
        binding.btnBack.setOnClickListener { finish() }
        // 4. Los resultados llegan cuando los estudiantes se conectan: recargar
        binding.btnRefresh.setOnClickListener { selectedTest?.let { loadResults(it) } ?: loadTests() }

        loadTests()
    }

    private fun token() = "Bearer ${session.token}"

    private fun showMessage(text: String?) {
        binding.tvMessage.text = text
        binding.tvMessage.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** 1. Trae las pruebas de esta materia y grupo para elegir una. */
    private fun loadTests() {
        showMessage("Cargando...")
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.tests(token(), subjectId, grade, group)
                if (!response.isSuccessful) {
                    showMessage(ApiClient.errorMessage(response))
                    return@launch
                }
                tests = response.body().orEmpty()
                if (tests.isEmpty()) {
                    binding.layoutTest.visibility = View.GONE
                    showMessage("📝\nNo hay pruebas en este grupo.\nCrea una en \"Pruebas\".")
                    return@launch
                }
                val names = tests.map { it.titulo }
                binding.acTest.setAdapter(ArrayAdapter(this@ResultsActivity, android.R.layout.simple_list_item_1, names))
                binding.acTest.setOnItemClickListener { _, _, position, _ ->
                    selectedTest = tests[position]
                    loadResults(tests[position])
                }
                selectedTest = tests[0]
                binding.acTest.setText(names[0], false)
                loadResults(tests[0])
            } catch (e: Exception) {
                showMessage("Sin conexión con el servidor.")
            }
        }
    }

    /** 2 y 3. Resultados de la prueba elegida. */
    private fun loadResults(test: TestSummary) {
        showMessage("Cargando resultados...")
        binding.layoutContent.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.testResults(token(), test.id)
                if (!response.isSuccessful) {
                    showMessage(ApiClient.errorMessage(response))
                    return@launch
                }
                show(response.body()!!)
            } catch (e: Exception) {
                showMessage("Sin conexión con el servidor.")
            }
        }
    }

    private fun show(r: TestResults) {
        showMessage(null)
        binding.layoutContent.visibility = View.VISIBLE

        // 2. Cuántos la presentaron y el promedio del grupo
        binding.tvPresented.text = "${r.presentaron} de ${r.groupStudents}\nla presentaron"
        binding.tvAverage.text = r.promedio?.let { "${formatNumber(it)} de ${r.prueba.totalQuestions}\npromedio" } ?: "--\npromedio"

        // 3. Nota de cada estudiante y quién no la ha hecho
        binding.listStudents.removeAllViews()
        if (r.students.isEmpty()) {
            binding.listStudents.addView(TextView(this).apply {
                text = "👧 No hay estudiantes en este grupo."
                textSize = 16f
                setPadding(0, 24, 0, 0)
            })
        }
        r.students.forEach { addStudentRow(it) }
    }

    private fun addStudentRow(s: StudentGrade) {
        val density = resources.displayMetrics.density
        val pad = (14 * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
        }
        val name = TextView(this).apply {
            text = s.nombre
            textSize = 17f
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            setTypeface(typeface, Typeface.BOLD)
        }
        val score = TextView(this).apply {
            setTypeface(typeface, Typeface.BOLD)
            if (s.falta) {
                // Aún no la presenta
                text = "Falta"
                textSize = 14f
                setTextColor(Color.parseColor("#B7791F"))
                setBackgroundColor(ContextCompat.getColor(context, R.color.yellow_soft))
                setPadding(pad / 2, pad / 4, pad / 2, pad / 4)
            } else {
                val correct = s.correctas ?: 0
                val total = s.total ?: 1
                text = "$correct/$total"
                textSize = 20f
                // Verde si sacó la mitad o más
                setTextColor(ContextCompat.getColor(context, if (correct * 2 >= total) R.color.green else R.color.red))
            }
        }
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(score)

        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.topMargin = (8 * density).toInt()
        binding.listStudents.addView(row, params)
    }

    /** 4.0 -> "4", 3.5 -> "3.5" */
    private fun formatNumber(x: Double): String =
        if (x % 1.0 == 0.0) x.toInt().toString() else String.format(Locale.US, "%.1f", x)
}