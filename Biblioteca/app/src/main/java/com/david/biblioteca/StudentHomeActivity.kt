package com.david.biblioteca

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivityStudentHomeBinding
import com.david.biblioteca.databinding.ItemSubjectBinding
import kotlinx.coroutines.launch

class StudentHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudentHomeBinding
    private lateinit var session: Session
    private lateinit var store: OfflineStore
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = Session(this)
        val user = session.user
        if (user == null) {
            goToStart()
            return
        }
        store = OfflineStore(this, user.id)
        binding.tvEmail.text = user.correo

        binding.btnLogout.setOnClickListener {
            session.clear()   // los archivos descargados se quedan en su carpeta
            goToStart()
        }

        // Actualizar: abre la pantalla de novedades y envíos
        binding.btnSync.setOnClickListener { startActivity(Intent(this, SyncActivity::class.java)) }

        // Secciones
        binding.btnTabGames.setOnClickListener { openIfReady(GamesActivity::class.java) }
        binding.btnTabAgenda.setOnClickListener { openIfReady(AgendaActivity::class.java) }
        binding.btnTabFacts.setOnClickListener { openIfReady(FactsActivity::class.java) }

        render()
        // La primera vez (sin nada guardado) baja todo solo
        if (store.loadSync() == null && isOnline(this)) firstSync()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) {
            updateConnection()
            render() // al volver de una materia o de Actualizar, todo se refresca
        }
    }

    private fun openIfReady(screen: Class<*>) {
        if (store.loadSync() == null) {
            Toast.makeText(this, "Primero toca Actualizar con internet", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(Intent(this, screen))
        }
    }

    private fun updateConnection() {
        if (isOnline(this)) {
            binding.tvConnection.text = "📶  Con internet: puedes actualizar"
            binding.bannerConnection.setBackgroundColor(Color.WHITE)
            binding.btnSync.visibility = View.VISIBLE
        } else {
            val pending = store.pendingResults().size + store.pendingRemoved().size
            binding.tvConnection.text = "✈️  Sin internet: usa lo que ya descargaste" +
                    if (pending > 0) " ($pending por enviar)" else ""
            binding.bannerConnection.setBackgroundColor(ContextCompat.getColor(this, R.color.yellow_soft))
            binding.btnSync.visibility = View.GONE
        }
    }

    /** Primera vez: baja la biblioteca sin abrir otra pantalla. */
    private fun firstSync() {
        if (syncing) return
        syncing = true
        binding.tvEmpty.text = "🦉\nDescargando tu biblioteca..."
        lifecycleScope.launch {
            try {
                SyncManager(this@StudentHomeActivity, session, store).sync()
            } catch (e: SyncManager.SessionExpired) {
                session.clear()
                goToStart()
                return@launch
            } catch (e: Exception) {
                Toast.makeText(this@StudentHomeActivity, "No se pudo descargar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                syncing = false
            }
            render()
        }
    }

    /** Dibuja el panel de materias con lo guardado en el celular (funciona sin internet). */
    private fun render() {
        val data = store.loadSync()
        binding.gridSubjects.removeAllViews()

        if (data == null) {
            binding.tvEmpty.visibility = View.VISIBLE
            if (!syncing) {
                binding.tvEmpty.text = "🦉\nConéctate a internet una vez y toca Actualizar para bajar tu biblioteca.\nDespués funciona sin conexión."
            }
            binding.tvDownloads.text = ""
            return
        }
        binding.tvEmpty.visibility = View.GONE

        val seen = store.seen()
        data.materias.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { subject ->
                val item = ItemSubjectBinding.inflate(layoutInflater, row, false)
                item.tvIcon.text = subject.icono
                item.tvName.text = subject.nombre
                item.card.setCardBackgroundColor(parseColor(subject.color))

                val news = newsInSubject(data, subject.id, seen)
                item.tvBadge.visibility = if (news > 0) View.VISIBLE else View.GONE
                item.tvBadge.text = if (news > 9) "9+" else news.toString()

                item.card.setOnClickListener {
                    val intent = Intent(this, SubjectActivity::class.java)
                    intent.putExtra("SUBJECT_ID", subject.id)
                    startActivity(intent)
                }
                row.addView(item.root)
            }
            if (pair.size == 1) {
                // Relleno para que la última materia no quede estirada
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            binding.gridSubjects.addView(row)
        }

        binding.tvDownloads.text = "${store.downloadedCount()} de ${OfflineStore.MAX_DOWNLOADS} descargas"
    }

    /** Número rojo: material, pruebas y juegos del profe que aún no ha visto. */
    private fun newsInSubject(data: SyncData, subjectId: Long, seen: Set<String>): Int =
        data.materiales.count { it.subjectId == subjectId && OfflineStore.materialKey(it) !in seen } +
                data.pruebas.count { it.subjectId == subjectId && OfflineStore.testKey(it) !in seen } +
                data.juegos.count { it.subjectId == subjectId && it.byTeacher && OfflineStore.gameKey(it) !in seen }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: Exception) {
        ContextCompat.getColor(this, R.color.coral)
    }

    private fun goToStart() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}