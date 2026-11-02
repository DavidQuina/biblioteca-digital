package com.david.biblioteca

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.david.biblioteca.databinding.ActivityGamesBinding
import com.david.biblioteca.databinding.ItemClassBinding

class GamesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGamesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        val user = Session(this).user ?: run { finish(); return }
        val data = OfflineStore(this, user.id).loadSync() ?: run { finish(); return }

        // Primero los del profesor, luego los de la app
        data.juegos.sortedByDescending { it.byTeacher }.forEach { g ->
            val subject = data.materias.firstOrNull { it.id == g.subjectId }
            val card = ItemClassBinding.inflate(layoutInflater, binding.listGames, false)
            card.tvIcon.text = when (g.tipo) { "memoria" -> "🃏"; "reloj" -> "⏱️"; else -> "🔗" }
            card.tvTitle.text = g.titulo
            card.tvInfo.text = if (g.byTeacher) "Hecho por tu profe · ${subject?.nombre ?: ""}" else (subject?.nombre ?: "")
            card.tvNew.visibility = View.GONE
            card.btnAction.text = "Jugar"
            val color = try { Color.parseColor(subject?.color ?: "#A259FF") } catch (e: Exception) { Color.MAGENTA }
            card.btnAction.backgroundTintList = ColorStateList.valueOf(color)
            card.btnAction.setOnClickListener {
                startActivity(Intent(this, GameActivity::class.java).putExtra("GAME_ID", g.id))
            }
            binding.listGames.addView(card.root)
        }
    }
}