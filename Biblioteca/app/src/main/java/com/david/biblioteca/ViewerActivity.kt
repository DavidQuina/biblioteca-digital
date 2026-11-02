package com.david.biblioteca

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.david.biblioteca.databinding.ActivityViewerBinding
import java.io.File

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding

    // Para el PDF
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val user = Session(this).user ?: run { finish(); return }
        val store = OfflineStore(this, user.id)
        val materialId = intent.getLongExtra("MATERIAL_ID", 0)
        val classNumber = intent.getIntExtra("CLASS_NUMBER", 1)

        val material = store.loadSync()?.materiales?.firstOrNull { it.id == materialId }
        val file = store.materialFile(materialId)
        if (material == null || !file.exists()) {
            Toast.makeText(this, "Este material no está en el celular. Descárgalo con internet.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.btnBack.text = "← Clase $classNumber"
        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = material.titulo
        // 1. Avisa que no hay internet, pero el material sigue ahí
        binding.tvOffline.visibility = if (isOnline(this)) View.GONE else View.VISIBLE

        // 2. Muestra según el tipo
        when {
            isPdf(material, file) -> showPdf(file)
            material.tipo == "video" || material.mime.startsWith("video/") -> showVideo(file)
            material.tipo == "imagen" || material.mime.startsWith("image/") -> showImage(file)
            else -> showOther(material, file)
        }
    }

    private fun isPdf(m: SyncMaterial, file: File): Boolean {
        if (m.mime == "application/pdf" || m.fileName.endsWith(".pdf", ignoreCase = true)) return true
        // Revisa los primeros bytes del archivo: los PDF empiezan con "%PDF"
        return try {
            file.inputStream().use { input ->
                val bytes = ByteArray(4)
                input.read(bytes)
                String(bytes) == "%PDF"
            }
        } catch (e: Exception) {
            false
        }
    }

    //  PDF
    private fun showPdf(file: File) {
        try {
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(fileDescriptor!!)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el PDF", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        binding.pdfControls.visibility = View.VISIBLE
        // 3. Anterior y Siguiente
        binding.btnPrev.setOnClickListener { showPage(page - 1) }
        binding.btnNext.setOnClickListener { showPage(page + 1) }
        showPage(0)
    }

    private fun showPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index < 0 || index >= renderer.pageCount) return
        page = index

        renderer.openPage(index).use { pdfPage ->
            // Dibuja la página en una imagen con fondo blanco
            val width = 1200
            val height = (width.toFloat() * pdfPage.height / pdfPage.width).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            binding.imgContent.setImageBitmap(bitmap)
        }

        binding.tvPage.text = "Página ${index + 1} de ${renderer.pageCount}"
        binding.btnPrev.isEnabled = index > 0
        binding.btnNext.isEnabled = index < renderer.pageCount - 1
    }

    // Video
    private fun showVideo(file: File) {
        binding.imgContent.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
        val controls = MediaController(this)
        controls.setAnchorView(binding.videoView)
        binding.videoView.setMediaController(controls)
        binding.videoView.setVideoPath(file.absolutePath)
        binding.videoView.setOnPreparedListener { binding.videoView.start() }
        binding.videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "No se pudo reproducir el video", Toast.LENGTH_LONG).show()
            true
        }
    }

    //  Imagen 
    private fun showImage(file: File) {
        binding.imgContent.setImageURI(Uri.fromFile(file))
    }

    //Otros (Word, formularios...)
    private fun showOther(m: SyncMaterial, file: File) {
        binding.imgContent.visibility = View.GONE
        binding.layoutOther.visibility = View.VISIBLE
        binding.tvOtherName.text = m.fileName
        binding.btnOpenWith.setOnClickListener {
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, m.mime.ifBlank { "*/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, "Abrir con"))
            } catch (e: Exception) {
                Toast.makeText(this, "No hay una app en el celular para abrir este archivo", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { pdfRenderer?.close() } catch (e: Exception) { }
        try { fileDescriptor?.close() } catch (e: Exception) { }
    }
}