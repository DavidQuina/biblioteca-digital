package com.david.biblioteca

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** ¿Hay Wi-Fi o datos? */
fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

fun iconForType(type: String) = when (type) {
    "pdf" -> "📄"
    "video" -> "🎬"
    "imagen" -> "🖼️"
    "examen" -> "📝"
    "formulario" -> "📋"
    "taller" -> "✏️"
    else -> "📁"
}

fun typeName(type: String) = when (type) {
    "pdf" -> "PDF"
    "video" -> "Video"
    "imagen" -> "Imagen"
    "examen" -> "Examen"
    "formulario" -> "Formulario"
    "taller" -> "Taller"
    else -> type
}

fun readableSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}