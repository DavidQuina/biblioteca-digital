package com.david.biblioteca

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivityTestBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestBinding
    private lateinit var session: Session
    private lateinit var store: OfflineStore
    private lateinit var test: SyncTest

    private var color = Color.parseColor("#A259FF")
    private var index = 0                       // pregunta actual
    private var chosen: Int? = null             // opción elegida en la pregunta actual
    private val answers = mutableListOf<Int>()  // respuestas del estudiante
    private var practice = false                // true = práctica (no cuenta)
    private var previous: MyResult? = null      // nota del primer intento

    private val letters = listOf("A", "B", "C", "D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = Session(this)
        val user = session.user ?: run { finish(); return }
        store = OfflineStore(this, user.id)

        val data = store.loadSync() ?: run { finish(); return }
        val testId = intent.getLongExtra("TEST_ID", 0)
        test = data.pruebas.firstOrNull { it.id == testId } ?: run { finish(); return }
        val subject = data.materias.firstOrNull { it.id == test.subjectId }

        color = try { Color.parseColor(subject?.color ?: "#A259FF") } catch (e: Exception) { color }
        binding.header.setBackgroundColor(color)
        binding.btnBack.text = "← ${subject?.nombre ?: "Volver"}"
        binding.btnBack.setOnClickListener { finish() }
        binding.tvTestTitle.text = test.titulo
        binding.progressBar.setIndicatorColor(color)
        binding.btnNext.backgroundTintList = ColorStateList.valueOf(color)
        binding.btnFinish.backgroundTintList = ColorStateList.valueOf(color)
        binding.tvScore.setTextColor(color)

        options().forEachIndexed { i, button -> button.setOnClickListener { choose(i) } }
        binding.btnNext.setOnClickListener { next() }
        binding.btnFinish.setOnClickListener { finish() }
        binding.btnPractice.setOnClickListener {
            practice = true
            startQuiz()
        }

        // Solo cuenta el primer intento
        previous = test.myResult ?: store.localResult(test.id)
        val prev = previous
        if (prev != null) {
            showResult(prev.correctas, prev.total, "Ya presentaste esta prueba. Solo cuenta el primer intento.")
        } else {
            startQuiz()
        }
    }

    private fun options(): List<MaterialButton> = listOf(binding.optA, binding.optB, binding.optC, binding.optD)

    private fun startQuiz() {
        index = 0
        answers.clear()
        binding.layoutResult.visibility = View.GONE
        binding.layoutQuiz.visibility = View.VISIBLE
        showQuestion()
    }

    /** 2. Muestra la pregunta actual con sus opciones. */
    private fun showQuestion() {
        val q = test.preguntas[index]
        chosen = null
        binding.tvProgress.text = "Pregunta ${index + 1} de ${test.preguntas.size}"
        binding.progressBar.progress = ((index + 1) * 100) / test.preguntas.size
        binding.tvQuestion.text = q.enunciado
        binding.tvFeedback.visibility = View.GONE
        binding.btnNext.visibility = View.GONE

        options().forEachIndexed { i, button ->
            if (i < q.opciones.size) {
                button.visibility = View.VISIBLE
                button.text = "${letters[i]}.  ${q.opciones[i]}"
                button.isEnabled = true
                paint(button, Color.WHITE, ContextCompat.getColor(this, R.color.gray_light))
            } else {
                button.visibility = View.GONE
            }
        }
    }

    /** 3 y 4. Elige una opción y ve al instante si acertó. */
    private fun choose(i: Int) {
        if (chosen != null) return
        chosen = i
        val q = test.preguntas[index]
        val green = ContextCompat.getColor(this, R.color.green)
        val red = ContextCompat.getColor(this, R.color.red)

        options().forEachIndexed { j, button ->
            button.isEnabled = false
            when (j) {
                q.correcta -> paint(button, ContextCompat.getColor(this, R.color.green_soft), green)
                i -> paint(button, Color.parseColor("#FFE5E5"), red)
            }
        }

        binding.tvFeedback.visibility = View.VISIBLE
        if (i == q.correcta) {
            binding.tvFeedback.text = "¡Muy bien! 🎉"
            binding.tvFeedback.setTextColor(green)
        } else {
            binding.tvFeedback.text = "¡Casi! La correcta es ${letters[q.correcta]}. ${q.opciones[q.correcta]}"
            binding.tvFeedback.setTextColor(red)
        }

        binding.btnNext.visibility = View.VISIBLE
        binding.btnNext.text = if (index < test.preguntas.size - 1) "Siguiente" else "Ver mi puntaje"
    }

    private fun paint(button: MaterialButton, background: Int, border: Int) {
        button.backgroundTintList = ColorStateList.valueOf(background)
        button.strokeColor = ColorStateList.valueOf(border)
    }

    /** 5. Siguiente pregunta o puntaje final. */
    private fun next() {
        answers.add(chosen ?: return)
        if (index < test.preguntas.size - 1) {
            index++
            showQuestion()
        } else {
            finishQuiz()
        }
    }

    private fun finishQuiz() {
        val correct = test.preguntas.indices.count { answers.getOrNull(it) == test.preguntas[it].correcta }
        val total = test.preguntas.size

        if (practice) {
            val prev = previous
            showResult(correct, total, "Esto fue práctica. Tu nota guardada sigue siendo ${prev?.correctas}/${prev?.total}.")
            return
        }

        // Primer intento: se guarda en el celular y queda pendiente de enviar
        val result = MyResult(correct, total)
        store.saveLocalResult(test.id, result)
        store.addPendingResult(PendingResult(test.id, answers.toList(), nowIso()))
        previous = result

        if (!isOnline(this)) {
            showResult(correct, total, "Sin internet: se enviará al profesor cuando toques Actualizar.")
            return
        }
        showResult(correct, total, "Enviando al profesor...")
        lifecycleScope.launch {
            val note = try {
                val response = ApiClient.api.send(
                    "Bearer ${session.token}",
                    SendRequest(store.pendingResults(), emptyList())
                )
                if (response.isSuccessful) {
                    store.clearPendingResults()
                    "✅ Tu resultado se envió al profesor."
                } else {
                    "Se enviará al profesor cuando toques Actualizar."
                }
            } catch (e: Exception) {
                "Se enviará al profesor cuando toques Actualizar."
            }
            binding.tvResultNote.text = note
        }
    }

    private fun showResult(correct: Int, total: Int, note: String) {
        binding.layoutQuiz.visibility = View.GONE
        binding.layoutResult.visibility = View.VISIBLE

        val ratio = if (total == 0) 0f else correct.toFloat() / total
        val stars = when {
            ratio >= 0.99f -> 3
            ratio >= 0.6f -> 2
            ratio > 0f -> 1
            else -> 0
        }
        binding.tvResultTitle.text = when (stars) {
            3 -> "¡Excelente!"
            2 -> "¡Muy bien!"
            1 -> "¡Sigue practicando!"
            else -> "¡Tú puedes!"
        }
        binding.tvStars.text = "⭐".repeat(stars) + "☆".repeat(3 - stars)
        binding.tvScore.text = "$correct de $total"
        binding.tvResultNote.text = note
        binding.btnPractice.visibility = if (previous != null) View.VISIBLE else View.GONE
    }

    /** Fecha y hora actual en formato del servidor (UTC). */
    private fun nowIso(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}