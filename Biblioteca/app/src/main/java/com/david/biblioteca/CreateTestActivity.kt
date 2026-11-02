package com.david.biblioteca

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivityCreateTestBinding
import kotlinx.coroutines.launch

class CreateTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateTestBinding
    private lateinit var session: Session

    // Llegan desde el inicio del profesor
    private var subjectId = 0L
    private var subjectName = ""
    private var grade = 3
    private var group = "A"

    // Preguntas ya agregadas
    private val questions = mutableListOf<QuestionRequest>()

    // Índice de la opción correcta en el editor (0 = A, 1 = B...)
    private var correct = 0
    private val letters = listOf("A", "B", "C", "D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = Session(this)
        subjectId = intent.getLongExtra("SUBJECT_ID", 0)
        subjectName = intent.getStringExtra("SUBJECT_NAME") ?: ""
        grade = intent.getIntExtra("GRADE", 3)
        group = intent.getStringExtra("GROUP") ?: "A"
        binding.tvContext.text = "$subjectName $grade° $group"

        binding.btnBack.setOnClickListener { finish() }

        // Tocar el círculo de una opción la marca como correcta
        radios().forEachIndexed { index, radio ->
            radio.setOnClickListener { selectCorrect(index) }
        }

        binding.btnAddOption.setOnClickListener {
            binding.rowD.visibility = View.VISIBLE
            binding.btnAddOption.visibility = View.GONE
        }

        binding.btnAddQuestion.setOnClickListener {
            if (addCurrentQuestion()) {
                Toast.makeText(this, "Pregunta agregada ✓", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPublish.setOnClickListener { publish() }

        selectCorrect(0)
        renderQuestions()
    }

    private fun radios() = listOf(binding.rbA, binding.rbB, binding.rbC, binding.rbD)
    private fun rows() = listOf(binding.rowA, binding.rowB, binding.rowC, binding.rowD)
    private fun optionFields() = listOf(binding.etOptionA, binding.etOptionB, binding.etOptionC, binding.etOptionD)

    /** Marca la opción correcta: el círculo queda seleccionado y la fila en verde. */
    private fun selectCorrect(index: Int) {
        correct = index
        val green = ContextCompat.getColor(this, R.color.green_soft)
        radios().forEachIndexed { i, radio -> radio.isChecked = i == index }
        rows().forEachIndexed { i, row ->
            row.setBackgroundColor(if (i == index) green else android.graphics.Color.TRANSPARENT)
        }
    }

    /** Revisa la pregunta del editor y la agrega a la lista. Devuelve false si falta algo. */
    private fun addCurrentQuestion(): Boolean {
        val text = binding.etQuestion.text.toString().trim()
        val fields = optionFields()

        // Opciones escritas, con su posición original (A, B, C, D)
        val filled = fields.mapIndexedNotNull { i, field ->
            val value = field.text.toString().trim()
            if (value.isNotEmpty() && (i < 3 || binding.rowD.visibility == View.VISIBLE)) i to value else null
        }

        val error = when {
            text.isEmpty() -> "Escribe la pregunta"
            filled.size < 2 -> "Escribe al menos 2 opciones"
            filled.none { it.first == correct } -> "Escribe el texto de la opción correcta (la que está en verde)"
            else -> null
        }
        if (error != null) {
            showError(error)
            return false
        }
        showError(null)

        questions.add(
            QuestionRequest(
                enunciado = text,
                opciones = filled.map { it.second },
                correcta = filled.indexOfFirst { it.first == correct }
            )
        )
        clearEditor()
        renderQuestions()
        return true
    }

    private fun clearEditor() {
        binding.etQuestion.setText("")
        optionFields().forEach { it.setText("") }
        binding.rowD.visibility = View.GONE
        binding.btnAddOption.visibility = View.VISIBLE
        selectCorrect(0)
        binding.etQuestion.requestFocus()
    }

    /** Dibuja la lista de preguntas agregadas. */
    private fun renderQuestions() {
        binding.listQuestions.removeAllViews()
        binding.tvQuestionsLabel.visibility = if (questions.isEmpty()) View.GONE else View.VISIBLE

        val density = resources.displayMetrics.density
        questions.forEachIndexed { index, q ->
            val lines = StringBuilder("${index + 1}. ${q.enunciado}")
            q.opciones.forEachIndexed { i, option ->
                lines.append("\n     ${letters[i]}. $option")
                if (i == q.correcta) lines.append("  ✓ correcta")
            }

            val item = TextView(this).apply {
                text = lines.toString()
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.ink))
                setBackgroundColor(ContextCompat.getColor(context, R.color.green_soft))
                val p = (14 * density).toInt()
                setPadding(p, p, p, p)
                setOnClickListener { askRemove(index) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = (8 * density).toInt()
            binding.listQuestions.addView(item, params)
        }

        binding.tvQuestionNumber.text = "Pregunta ${questions.size + 1}"
        binding.btnPublish.text = if (questions.isEmpty()) "Publicar prueba" else "Publicar prueba (${questions.size} preguntas)"
    }

    private fun askRemove(index: Int) {
        AlertDialog.Builder(this)
            .setTitle("¿Quitar la pregunta ${index + 1}?")
            .setPositiveButton("Quitar") { _, _ ->
                questions.removeAt(index)
                renderQuestions()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun publish() {
        val title = binding.etTestTitle.text.toString().trim()
        if (title.isEmpty()) {
            showError("Escribe el nombre de la evaluación")
            return
        }
        // Si dejó una pregunta escrita en el editor, se agrega sola
        if (binding.etQuestion.text.toString().isNotBlank() && !addCurrentQuestion()) return
        if (questions.isEmpty()) {
            showError("Agrega al menos una pregunta")
            return
        }
        showError(null)
        setLoading(true)

        lifecycleScope.launch {
            try {
                val response = ApiClient.api.createTest(
                    "Bearer ${session.token}",
                    CreateTestRequest(title, subjectId, grade, group, questions.toList())
                )
                if (response.isSuccessful) {
                    Toast.makeText(
                        this@CreateTestActivity,
                        "¡Prueba publicada! Los estudiantes la responden en la app.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
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

    private fun setLoading(loading: Boolean) {
        binding.btnPublish.isEnabled = !loading
        if (loading) binding.btnPublish.text = "Publicando..." else renderQuestions()
    }

    private fun showError(message: String?) {
        binding.tvError.text = message
        binding.tvError.visibility = if (message == null) View.GONE else View.VISIBLE
    }
}