package com.david.biblioteca

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.david.biblioteca.databinding.ActivityFactsBinding
import com.david.biblioteca.databinding.ItemFactBinding

class FactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFactsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFactsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        val user = Session(this).user ?: run { finish(); return }
        val data = OfflineStore(this, user.id).loadSync() ?: run { finish(); return }

        // 2. Datos curiosos de cada materia (funcionan sin internet)
        binding.tvEmpty.visibility = if (data.datos.isEmpty()) View.VISIBLE else View.GONE
        data.datos.forEach { fact ->
            val subject = data.materias.firstOrNull { it.id == fact.subjectId }
            val card = ItemFactBinding.inflate(layoutInflater, binding.listFacts, false)
            card.tvTitle.text = "¿Sabías que...?"
            card.tvText.text = fact.texto
            card.tvSubject.text = subject?.let { "${it.icono} ${it.nombre}" } ?: ""
            // Fondo suave con el color de la materia
            val color = try { Color.parseColor(subject?.color ?: "#FFB020") } catch (e: Exception) { Color.YELLOW }
            card.card.setCardBackgroundColor(ColorUtils.blendARGB(Color.WHITE, color, 0.18f))
            binding.listFacts.addView(card.root)
        }

        // 3. Enlaces externos (no son obligatorios)
        val links = data.datos.filter { !it.enlace.isNullOrBlank() }
        val showLinks = if (links.isEmpty()) View.GONE else View.VISIBLE
        binding.tvLinksTitle.visibility = showLinks
        binding.tvLinksHint.visibility = showLinks

        links.forEach { fact ->
            val subject = data.materias.firstOrNull { it.id == fact.subjectId }
            val card = ItemFactBinding.inflate(layoutInflater, binding.listLinks, false)
            card.tvTitle.text = "🔗 Para saber más"
            card.tvSubject.text = subject?.let { "${it.icono} ${it.nombre}" } ?: ""
            card.tvText.text = fact.texto
            card.card.setCardBackgroundColor(Color.WHITE)
            card.btnOpen.visibility = View.VISIBLE
            card.btnOpen.setOnClickListener { openLink(fact.enlace!!) }
            binding.listLinks.addView(card.root)
        }
    }

    override fun onResume() {
        super.onResume()
        // Botones de enlace en gris si no hay internet
        val color = ContextCompat.getColor(this, if (isOnline(this)) R.color.blue else R.color.gray)
        for (i in 0 until binding.listLinks.childCount) {
            val card = ItemFactBinding.bind(binding.listLinks.getChildAt(i))
            card.btnOpen.backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    /** 4. Abrir un enlace externo sí necesita internet. */
    private fun openLink(url: String) {
        if (!isOnline(this)) {
            Toast.makeText(this, "Para abrir enlaces necesitas internet 📶", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No hay un navegador para abrir el enlace", Toast.LENGTH_LONG).show()
        }
    }
}