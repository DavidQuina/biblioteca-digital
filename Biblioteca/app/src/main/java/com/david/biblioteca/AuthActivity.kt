package com.david.biblioteca

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.david.biblioteca.databinding.ActivityAuthBinding
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var role: Role
    private var isCreateMode = true

    private val grades = (1..11).map { "$it°" }
    private val groups = listOf("A", "B", "C", "D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Rol que llega desde MainActivity
        role = Role.valueOf(intent.getStringExtra("ROLE") ?: Role.STUDENT.name)

        // Color según el rol: azul profesor, verde estudiante
        val color = ContextCompat.getColor(this, if (role == Role.TEACHER) R.color.blue else R.color.green)
        binding.btnSubmit.backgroundTintList = ColorStateList.valueOf(color)

        // Listas de grado y grupo
        binding.acGrade.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, grades))
        binding.acGroup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, groups))
        binding.acGrade.setText(grades[2], false)   // 3° por defecto
        binding.acGroup.setText(groups[0], false)   // A por defecto

        binding.btnBack.setOnClickListener { finish() }

        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isCreateMode = checkedId == R.id.btnModeCreate
                updateMode()
            }
        }

        binding.btnSubmit.setOnClickListener { submit() }

        updateMode()
    }

    /** Muestra u oculta campos según "Crear cuenta" o "Ya tengo cuenta". */
    private fun updateMode() {
        binding.tvTitle.text = if (isCreateMode) "Crea tu cuenta de ${role.title}" else "Ingresa como ${role.title}"
        binding.layoutName.visibility = if (isCreateMode) View.VISIBLE else View.GONE
        binding.layoutGradeGroup.visibility =
            if (isCreateMode && role == Role.STUDENT) View.VISIBLE else View.GONE
        binding.btnSubmit.text = if (isCreateMode) "Crear cuenta" else "Entrar"
        binding.tvHint.text = if (role == Role.TEACHER)
            "El profesor necesita internet para publicar."
        else
            "Necesitas internet solo para crear la cuenta y descargar. ¿Sin celular? Usa la tablet del colegio con tu propia cuenta."
        showError(null)
    }

    private fun submit() {
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        val error = when {
            isCreateMode && name.isEmpty() -> "Escribe tu nombre"
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Escribe un correo válido"
            password.length < 6 -> "La contraseña debe tener al menos 6 caracteres"
            else -> null
        }
        if (error != null) {
            showError(error)
            return
        }
        showError(null)

        val grade = grades.indexOf(binding.acGrade.text.toString()) + 1
        val group = binding.acGroup.text.toString()

        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = if (isCreateMode) {
                    ApiClient.api.register(
                        RegisterRequest(
                            rol = role.apiName,
                            nombre = name,
                            correo = email,
                            clave = password,
                            grado = if (role == Role.STUDENT) grade else null,
                            grupo = if (role == Role.STUDENT) group else null
                        )
                    )
                } else {
                    ApiClient.api.login(LoginRequest(email, password, role.apiName))
                }

                if (response.isSuccessful) {
                    val auth = response.body()!!
                    Session(this@AuthActivity).save(auth)
                    Toast.makeText(this@AuthActivity, "¡Bienvenido, ${auth.user.nombre}!", Toast.LENGTH_LONG).show()
                    // Abre el inicio según el rol
                    val home = if (auth.user.rol == "profesor") TeacherHomeActivity::class.java
                    else StudentHomeActivity::class.java
                    val intent = Intent(this@AuthActivity, home)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                } else {
                    showError(ApiClient.errorMessage(response))
                }
            } catch (e: Exception) {
                showError("No hay conexión con el servidor. Revisa tu internet e intenta de nuevo.")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnSubmit.isEnabled = !loading
        binding.btnSubmit.text = when {
            loading -> "Cargando..."
            isCreateMode -> "Crear cuenta"
            else -> "Entrar"
        }
    }

    private fun showError(message: String?) {
        binding.tvError.text = message
        binding.tvError.visibility = if (message == null) View.GONE else View.VISIBLE
    }
}