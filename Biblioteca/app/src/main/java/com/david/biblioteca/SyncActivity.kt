package com.david.biblioteca

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivitySyncBinding
import kotlinx.coroutines.launch

class SyncActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncBinding
    private lateinit var session: Session
    private lateinit var store: OfflineStore
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        session = Session(this)
        val user = session.user ?: run { finish(); return }
        store = OfflineStore(this, user.id)

        binding.btnSync.setOnClickListener { sync() }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) render()
    }

    private fun render() {
        // 1. Conexión
        val online = isOnline(this)
        binding.tvConnection.text = if (online) "📶  Hay conexión: puedes actualizar"
        else "✈️  Sin internet. Conéctate a una red para actualizar."
        binding.tvConnection.setBackgroundColor(
            ContextCompat.getColor(this, if (online) R.color.green_soft else R.color.yellow_soft)
        )
        binding.btnSync.isEnabled = online && !syncing

        // 3. Novedades
        val news = store.news()
        binding.listNews.removeAllViews()
        binding.tvNoNews.visibility = if (news.isEmpty()) View.VISIBLE else View.GONE
        news.forEach { addNewsRow(it) }

        // 4. Pendientes
        val tests = store.pendingResults().size
        val removed = store.pendingRemoved().size
        binding.tvPendingTests.text = "📝 $tests ${if (tests == 1) "prueba resuelta" else "pruebas resueltas"}"
        binding.tvPendingRemoved.text = "📥 $removed ${if (removed == 1) "descarga quitada" else "descargas quitadas"}"
        binding.tvPendingNote.text = if (tests + removed == 0)
            "Todo lo que hiciste sin internet ya llegó al profesor. ✅"
        else
            "Se envía cuando tocas Actualizar con internet."

        // 5. Contador
        val count = store.downloadedCount()
        binding.tvCounter.text = "$count de ${OfflineStore.MAX_DOWNLOADS} descargas"
        binding.tvCounter.setTextColor(
            ContextCompat.getColor(this, if (count >= OfflineStore.MAX_DOWNLOADS) R.color.red else R.color.green)
        )
    }

    /** Una fila de novedad con la etiqueta "Nuevo". */
    private fun addNewsRow(text: String) {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.white))
            setPadding(pad, pad, pad, pad)
        }
        val tvText = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            setTypeface(typeface, Typeface.BOLD)
        }
        val chip = TextView(this).apply {
            this.text = "Nuevo"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            setBackgroundColor(ContextCompat.getColor(context, R.color.red))
            setPadding(pad / 2, pad / 4, pad / 2, pad / 4)
            setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(tvText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip)

        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.topMargin = (8 * density).toInt()
        binding.listNews.addView(row, params)
    }

    /** 2. El botón Actualizar. */
    private fun sync() {
        if (syncing) return
        if (!isOnline(this)) {
            Toast.makeText(this, "Sin internet. Conéctate para actualizar.", Toast.LENGTH_SHORT).show()
            return
        }
        syncing = true
        binding.btnSync.isEnabled = false
        binding.btnSync.text = "Actualizando..."

        lifecycleScope.launch {
            try {
                val result = SyncManager(this@SyncActivity, session, store).sync()
                val message = buildString {
                    append(if (result.news.isEmpty()) "¡Todo al día!" else "¡Llegaron ${result.news.size} novedades!")
                    if (result.sent > 0) append(" Se enviaron ${result.sent} cosas al profesor.")
                }
                Toast.makeText(this@SyncActivity, message, Toast.LENGTH_LONG).show()
                result.warnings.forEach { Toast.makeText(this@SyncActivity, it, Toast.LENGTH_LONG).show() }
            } catch (e: SyncManager.SessionExpired) {
                Toast.makeText(this@SyncActivity, e.message, Toast.LENGTH_LONG).show()
                session.clear()
                val intent = Intent(this@SyncActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                return@launch
            } catch (e: Exception) {
                Toast.makeText(this@SyncActivity, "No se pudo actualizar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                syncing = false
                binding.btnSync.text = "🔄  Actualizar"
            }
            render()
        }
    }
}