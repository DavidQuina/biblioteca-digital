package com.david.biblioteca

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivityCreateGameBinding
import com.david.biblioteca.databinding.ItemClassBinding
import com.david.biblioteca.databinding.ItemPairBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class CreateGameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateGameBinding
    private lateinit var session: Session

    private var subjectId = 0L
    private var subjectName = ""
    private var grade = 3
    private var group = "A"

    // Tipos de juego: (valor del servidor, nombre para el profesor)
    private val types = listOf(
        "memoria" to "🃏 Memoria de parejas",
        "unir" to "🔗 Une palabra e imagen",
        "reloj" to "⏱️ Quiz contra el reloj"
    )
    private var selectedType = "unir"

    /** Cada pareja: su vista y la foto subida (si tiene). */
    private class PairRow(val view: ItemPairBinding) {
        var photoName: String? = null
        var uploading = false
    }
    private val pairs = mutableListOf<PairRow>()
    private var photoFor: PairRow? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val row = photoFor
        if (uri != null && row != null && row in pairs) uploadPhoto(row, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = Session(this)
        subjectId = intent.getLongExtra("SUBJECT_ID", 0)
        subjectName = intent.getStringExtra("SUBJECT_NAME") ?: ""
        grade = intent.getIntExtra("GRADE", 3)
        group = intent.getStringExtra("GROUP") ?: "A"
        binding.tvContext.text = "$subjectName $grade° $group"
        binding.btnBack.setOnClickListener { finish() }

        // 1. Tipo de juego
        binding.acType.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, types.map { it.second }))
        binding.acType.setText(types[1].second, false)
        binding.acType.setOnItemClickListener { _, _, position, _ -> selectedType = types[position].first }

        // 3 y 4. Parejas (empieza con 2)
        addPair()
        addPair()
        binding.btnAddPair.setOnClickListener {
            if (pairs.size >= 12) Toast.makeText(this, "Máximo 12 parejas", Toast.LENGTH_SHORT).show()
            else addPair()
        }

        // 5. Guardar
        binding.btnSave.setOnClickListener { save() }

        loadGames()
    }

    private fun token() = "Bearer ${session.token}"

    //  Parejas
    private fun addPair() {
        val view = ItemPairBinding.inflate(layoutInflater, binding.listPairs, false)
        val row = PairRow(view)
        pairs.add(row)

        // Al escribir el emoji se ve en el cuadrito
        view.etEmoji.doAfterTextChanged { text ->
            if (row.photoName == null) {
                val value = text.toString().trim()
                view.tvPreview.textSize = if (value.length <= 2) 28f else 14f
                view.tvPreview.text = value.ifEmpty { "📷" }
            }
        }
        // Tocar el cuadrito: poner una foto
        view.cardImage.setOnClickListener {
            photoFor = row
            pickPhoto.launch("image/*")
        }
        // Quitar la pareja (mínimo 2)
        view.btnRemove.setOnClickListener {
            if (pairs.size <= 2) {
                Toast.makeText(this, "El juego necesita al menos 2 parejas", Toast.LENGTH_SHORT).show()
            } else {
                pairs.remove(row)
                binding.listPairs.removeView(view.root)
            }
        }
        binding.listPairs.addView(view.root)
    }

    /** Sube la foto apenas se elige; el servidor devuelve su nombre. */
    private fun uploadPhoto(row: PairRow, uri: Uri) {
        var fileName = "foto.jpg"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && i >= 0) fileName = cursor.getString(i) ?: fileName
        }
        row.uploading = true
        row.view.progress.visibility = View.VISIBLE
        row.view.tvPreview.text = ""
        row.view.imgPhoto.visibility = View.VISIBLE
        row.view.imgPhoto.setImageURI(uri)

        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val part = MultipartBody.Part.createFormData("imagen", fileName, bytes.toRequestBody(mime.toMediaTypeOrNull()))
                val response = ApiClient.api.uploadGameImage(token(), part)
                if (response.isSuccessful) {
                    row.photoName = response.body()!!.archivo
                    row.view.layoutEmoji.visibility = View.GONE   // con foto ya no hace falta el emoji
                } else {
                    resetPhoto(row)
                    Toast.makeText(this@CreateGameActivity, ApiClient.errorMessage(response), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                resetPhoto(row)
                Toast.makeText(this@CreateGameActivity, "No se pudo subir la foto", Toast.LENGTH_LONG).show()
            } finally {
                row.uploading = false
                row.view.progress.visibility = View.GONE
            }
        }
    }

    private fun resetPhoto(row: PairRow) {
        row.photoName = null
        row.view.imgPhoto.visibility = View.GONE
        row.view.tvPreview.text = row.view.etEmoji.text.toString().ifBlank { "📷" }
    }

    //  Guardar
    private fun save() {
        val theme = binding.etTheme.text.toString().trim()
        val filled = pairs.filter { it.view.etWord.text.toString().isNotBlank() }

        val error = when {
            theme.isEmpty() -> "Escribe el tema del juego"
            filled.size < 2 -> "Agrega al menos 2 parejas"
            selectedType == "reloj" && filled.size < 3 -> "El quiz contra el reloj necesita al menos 3 parejas"
            filled.any { it.uploading } -> "Espera a que terminen de subir las fotos"
            filled.any { it.photoName == null && it.view.etEmoji.text.toString().isBlank() } ->
                "Cada pareja necesita una imagen (emoji, texto o foto)"
            else -> null
        }
        if (error != null) {
            showError(error)
            return
        }
        showError(null)

        val request = CreateGameRequest(
            tipo = selectedType,
            titulo = theme,
            materiaId = subjectId,
            grado = grade,
            grupo = group,
            parejas = filled.map {
                PairRequest(
                    palabra = it.view.etWord.text.toString().trim(),
                    imagen = it.view.etEmoji.text.toString().trim().ifBlank { null },
                    archivo = it.photoName
                )
            }
        )

        binding.btnSave.isEnabled = false
        binding.btnSave.text = "Guardando..."
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.createGame(token(), request)
                if (response.isSuccessful) {
                    Toast.makeText(
                        this@CreateGameActivity,
                        "¡Juego guardado! Aparece en Juegos, en la app de los estudiantes.",
                        Toast.LENGTH_LONG
                    ).show()
                    clearForm()
                    loadGames()
                } else {
                    showError(ApiClient.errorMessage(response))
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            } finally {
                binding.btnSave.isEnabled = true
                binding.btnSave.text = "Guardar juego"
            }
        }
    }

    private fun clearForm() {
        binding.etTheme.setText("")
        pairs.clear()
        binding.listPairs.removeAllViews()
        addPair()
        addPair()
    }

    // Mis juegos
    private fun loadGames() {
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.teacherGames(token(), subjectId, grade, group)
                if (!response.isSuccessful) return@launch
                val games = response.body().orEmpty()
                binding.listGames.removeAllViews()
                binding.tvNoGames.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE

                games.forEach { g ->
                    val card = ItemClassBinding.inflate(layoutInflater, binding.listGames, false)
                    card.tvIcon.text = when (g.tipo) { "memoria" -> "🃏"; "reloj" -> "⏱️"; else -> "🔗" }
                    card.tvTitle.text = g.titulo
                    card.tvInfo.text = "${types.firstOrNull { it.first == g.tipo }?.second ?: g.tipo} · ${g.parejas.size} parejas"
                    card.btnAction.text = "🗑 Borrar"
                    card.btnAction.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this@CreateGameActivity, R.color.red))
                    card.btnAction.setOnClickListener { askDelete(g) }
                    binding.listGames.addView(card.root)
                }
            } catch (e: Exception) {
                // sin conexión: la lista queda como está
            }
        }
    }

    private fun askDelete(g: TeacherGame) {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar el juego \"${g.titulo}\"?")
            .setMessage("Los estudiantes dejarán de verlo cuando actualicen.")
            .setPositiveButton("Borrar") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ApiClient.api.deleteGame(token(), g.id)
                        loadGames()
                    } catch (e: Exception) {
                        Toast.makeText(this@CreateGameActivity, "Sin conexión con el servidor", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showError(message: String?) {
        binding.tvError.text = message
        binding.tvError.visibility = if (message == null) View.GONE else View.VISIBLE
    }
}