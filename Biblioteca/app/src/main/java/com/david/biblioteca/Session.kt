package com.david.biblioteca



import android.content.Context
import com.google.gson.Gson

/** Guarda el token y el usuario para no tener que entrar cada vez. */
class Session(context: Context) {

    private val prefs = context.getSharedPreferences("sesion", Context.MODE_PRIVATE)

    fun save(auth: AuthResponse) {
        prefs.edit()
            .putString("token", auth.token)
            .putString("user", Gson().toJson(auth.user))
            .apply()
    }

    val token: String?
        get() = prefs.getString("token", null)

    val user: User?
        get() = prefs.getString("user", null)?.let { Gson().fromJson(it, User::class.java) }

    fun clear() {
        prefs.edit().clear().apply()
    }
}