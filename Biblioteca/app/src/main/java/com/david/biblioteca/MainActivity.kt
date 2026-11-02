package com.david.biblioteca

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.david.biblioteca.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Si ya hay sesión, entra directo a su inicio (el estudiante puede entrar SIN internet)
        when (Session(this).user?.rol) {
            "profesor" -> {
                startActivity(Intent(this, TeacherHomeActivity::class.java))
                finish()
                return
            }
            "estudiante" -> {
                startActivity(Intent(this, StudentHomeActivity::class.java))
                finish()
                return
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardTeacher.setOnClickListener { openAuth(Role.TEACHER) }
        binding.cardStudent.setOnClickListener { openAuth(Role.STUDENT) }
    }

    private fun openAuth(role: Role) {
        val intent = Intent(this, AuthActivity::class.java)
        intent.putExtra("ROLE", role.name)
        startActivity(intent)
    }
}