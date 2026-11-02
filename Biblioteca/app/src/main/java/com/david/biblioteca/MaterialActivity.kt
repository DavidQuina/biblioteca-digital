package com.david.biblioteca

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivityMaterialBinding
import com.david.biblioteca.databinding.ItemMaterialBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class MaterialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMaterialBinding
    private lateinit var session: Session

    // Llegan desde el inicio del profesor
    private var subjectId = 0L
    private var subjectName = ""
    private var grade = 3
    private var group = "A"

    // Material al que se le está cambiando el archivo
    private var updating: Material? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val material = updating
        if (uri != null && material != null) uploadNewVersion(material, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMaterialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = Session(this)
        subjectId = intent.getLongExtra("SUBJECT_ID", 0)
        subjectName = intent.getStringExtra("SUBJECT_NAME") ?: ""
        grade = intent.getIntExtra("GRADE", 3)
        group = intent.getStringExtra("GROUP") ?: "A"
        binding.tvContext.text = "$subjectName $grade° $group"

        binding.btnBack.setOnClickListener { finish() }

        loadMaterials()
    }

    private fun token() = "Bearer ${session.token}"

    /** Trae el material de esta materia, grado y grupo. */
    private fun loadMaterials() {
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.materials(token(), subjectId, grade, group)
                if (!response.isSuccessful) {
                    showMessage(ApiClient.errorMessage(response))
                    return@launch
                }
                val list = response.body().orEmpty()
                binding.listMaterials.removeAllViews()
                if (list.isEmpty()) {
                    showMessage("📂\nNo hay material en este grupo.\nSúbelo desde \"Subir\".")
                    return@launch
                }
                showMessage(null)
                list.forEach { addCard(it) }
            } catch (e: Exception) {
                showMessage("Sin conexión con el servidor.")
            }
        }
    }

    /** Crea la tarjeta de un material con sus 3 acciones. */
    private fun addCard(m: Material) {
        val card = ItemMaterialBinding.inflate(layoutInflater, binding.listMaterials, false)
        card.tvIcon.text = iconFor(m.tipo)
        card.tvTitle.text = m.titulo
        card.tvInfo.text = "$subjectName, grado $grade° · ${typeName(m.tipo)} · ${readableSize(m.tamano)} · v${m.version}"

        // 1. Visible / Oculto (primero se marca el estado actual y después se escucha el cambio)
        card.toggleVisible.check(if (m.visible) R.id.btnVisible else R.id.btnHidden)
        card.toggleVisible.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val visible = checkedId == R.id.btnVisible
            if (visible != m.visible) changeVisible(m, visible)
        }

        // 2. Actualizar archivo
        card.btnUpdate.setOnClickListener {
            updating = m
            pickFile.launch(mimeTypesFor(m.tipo))
        }

        // 3. Borrar
        card.btnDelete.setOnClickListener { askDelete(m) }

        binding.listMaterials.addView(card.root)
    }

    private fun changeVisible(m: Material, visible: Boolean) {
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.setVisible(token(), m.id, VisibleRequest(visible))
                if (response.isSuccessful) {
                    Toast.makeText(
                        this@MaterialActivity,
                        if (visible) "Ahora los estudiantes lo ven" else "Oculto: los estudiantes no lo ven",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@MaterialActivity, ApiClient.errorMessage(response), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MaterialActivity, "Sin conexión con el servidor", Toast.LENGTH_LONG).show()
            }
            loadMaterials() // recarga para mostrar el estado real
        }
    }

    private fun uploadNewVersion(m: Material, uri: Uri) {
        var fileName = "archivo"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
        }
        Toast.makeText(this, "Subiendo nueva versión...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                }
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val part = MultipartBody.Part.createFormData("archivo", fileName, bytes.toRequestBody(mime.toMediaTypeOrNull()))
                val response = ApiClient.api.updateFile(token(), m.id, part)
                if (response.isSuccessful) {
                    val version = response.body()?.version ?: (m.version + 1)
                    Toast.makeText(this@MaterialActivity, "Archivo actualizado (versión $version)", Toast.LENGTH_LONG).show()
                    loadMaterials()
                } else {
                    Toast.makeText(this@MaterialActivity, ApiClient.errorMessage(response), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MaterialActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                updating = null
            }
        }
    }

    private fun askDelete(m: Material) {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar \"${m.titulo}\"?")
            .setMessage("Los estudiantes dejarán de verlo cuando actualicen.")
            .setPositiveButton("Borrar") { _, _ -> delete(m) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun delete(m: Material) {
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.deleteMaterial(token(), m.id)
                if (response.isSuccessful) {
                    Toast.makeText(this@MaterialActivity, "Borrado", Toast.LENGTH_SHORT).show()
                    loadMaterials()
                } else {
                    Toast.makeText(this@MaterialActivity, ApiClient.errorMessage(response), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MaterialActivity, "Sin conexión con el servidor", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMessage(text: String?) {
        binding.tvMessage.text = text
        binding.tvMessage.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun mimeTypesFor(type: String): Array<String> = when (type) {
        "pdf" -> arrayOf("application/pdf")
        "video" -> arrayOf("video/*")
        "imagen" -> arrayOf("image/*")
        else -> arrayOf("*/*")
    }

    private fun iconFor(type: String) = when (type) {
        "pdf" -> "📄"
        "video" -> "🎬"
        "imagen" -> "🖼️"
        "examen" -> "📝"
        "formulario" -> "📋"
        "taller" -> "✏️"
        else -> "📁"
    }

    private fun typeName(type: String) = when (type) {
        "pdf" -> "PDF"
        "video" -> "Video"
        "imagen" -> "Imagen"
        "examen" -> "Examen"
        "formulario" -> "Formulario"
        "taller" -> "Taller"
        else -> type
    }

    private fun readableSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}