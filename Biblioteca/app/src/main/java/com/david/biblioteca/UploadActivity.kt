package com.david.biblioteca

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivityUploadBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class UploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUploadBinding
    private lateinit var session: Session

    // Llegan desde el inicio del profesor
    private var subjectId = 0L
    private var subjectName = ""
    private var grade = 3
    private var group = "A"

    // Archivo elegido
    private var fileUri: Uri? = null
    private var fileName = ""
    private var fileSize = 0L

    // Elegir archivo del celular
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = Session(this)
        subjectId = intent.getLongExtra("SUBJECT_ID", 0)
        subjectName = intent.getStringExtra("SUBJECT_NAME") ?: ""
        grade = intent.getIntExtra("GRADE", 3)
        group = intent.getStringExtra("GROUP") ?: "A"

        binding.tvContext.text = "$subjectName $grade° $group"
        binding.btnBack.setOnClickListener { finish() }
        binding.cardFile.setOnClickListener { pickFile.launch(mimeTypesFor(selectedType())) }
        binding.btnPublish.setOnClickListener { publish() }
    }

    /** Tipo elegido en los chips, tal como lo espera el servidor. */
    private fun selectedType(): String = when (binding.chipTypes.checkedChipId) {
        R.id.chipVideo -> "video"
        R.id.chipImage -> "imagen"
        R.id.chipExam -> "examen"
        R.id.chipForm -> "formulario"
        R.id.chipWorkshop -> "taller"
        else -> "pdf"
    }

    /** Qué archivos mostrar en el selector según el tipo. */
    private fun mimeTypesFor(type: String): Array<String> = when (type) {
        "pdf" -> arrayOf("application/pdf")
        "video" -> arrayOf("video/*")
        "imagen" -> arrayOf("image/*")
        else -> arrayOf("*/*")
    }

    private fun onFilePicked(uri: Uri) {
        fileUri = uri
        fileName = "archivo"
        fileSize = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
                if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
            }
        }
        binding.tvFileIcon.text = "✅"
        binding.tvFileName.text = fileName
        binding.tvFileSize.text = readableSize(fileSize)
        binding.tvFileSize.visibility = View.VISIBLE
        showError(null)
    }

    private fun publish() {
        val title = binding.etTitle.text.toString().trim()
        val uri = fileUri
        val error = when {
            title.isEmpty() -> "Escribe el título"
            uri == null -> "Adjunta el archivo"
            fileSize > 60L * 1024 * 1024 -> "El archivo pesa más de 60 MB"
            else -> null
        }
        if (error != null || uri == null) {
            showError(error)
            return
        }
        showError(null)
        setLoading(true)

        lifecycleScope.launch {
            try {
                // Leer el archivo (en segundo plano para no congelar la pantalla)
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                }
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val filePart = MultipartBody.Part.createFormData(
                    "archivo", fileName, bytes.toRequestBody(mime.toMediaTypeOrNull())
                )

                val response = ApiClient.api.uploadMaterial(
                    token = "Bearer ${session.token}",
                    title = text(title),
                    type = text(selectedType()),
                    subjectId = text(subjectId.toString()),
                    grade = text(grade.toString()),
                    group = text(group),
                    file = filePart
                )

                if (response.isSuccessful) {
                    Toast.makeText(
                        this@UploadActivity,
                        "¡Publicado! Los estudiantes de $grade° $group lo verán al actualizar.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish() // vuelve al inicio, que se recarga solo
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

    private fun text(value: String) = value.toRequestBody("text/plain".toMediaType())

    private fun readableSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun setLoading(loading: Boolean) {
        binding.btnPublish.isEnabled = !loading
        binding.btnPublish.text = if (loading) "Subiendo..." else "Publicar"
    }

    private fun showError(message: String?) {
        binding.tvError.text = message
        binding.tvError.visibility = if (message == null) View.GONE else View.VISIBLE
    }
}