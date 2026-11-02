package com.david.biblioteca

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Guarda en el celular todo lo del estudiante para usarlo SIN internet.
 * Cada cuenta tiene su propia carpeta (sirve para la tablet del colegio con varias cuentas).
 */
class OfflineStore(context: Context, userId: Long) {

    private val folder = File(context.filesDir, "u$userId").apply { mkdirs() }
    private val materialsFolder = File(folder, "materiales").apply { mkdirs() }
    private val syncFile = File(folder, "sync.json")
    private val prefs = context.getSharedPreferences("offline_u$userId", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Datos de la última actualización
    fun saveSync(json: String) {
        syncFile.writeText(json)
    }

    fun loadSync(): SyncData? {
        if (!syncFile.exists()) return null
        return try {
            gson.fromJson(syncFile.readText(), SyncData::class.java)
        } catch (e: Exception) {
            null
        }
    }

    // Novedades de la última actualización
    fun saveNews(news: List<String>) {
        prefs.edit().putString("news", gson.toJson(news)).apply()
    }

    fun news(): List<String> {
        val json = prefs.getString("news", null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Lo que el estudiante ya vio (para el número rojo)
    fun seen(): Set<String> = prefs.getStringSet("seen", emptySet()) ?: emptySet()

    fun markSeen(keys: Collection<String>) {
        prefs.edit().putStringSet("seen", seen() + keys).apply()
    }

    // Archivos descargados
    fun materialFile(materialId: Long): File = File(materialsFolder, materialId.toString())

    fun isDownloaded(materialId: Long): Boolean = materialFile(materialId).exists()

    /** Ids de los materiales que están en el celular. */
    fun downloadedIds(): List<Long> =
        materialsFolder.listFiles()?.mapNotNull { it.name.toLongOrNull() } ?: emptyList()

    /** Cuántos archivos tiene el estudiante en el celular (máximo 10). */
    fun downloadedCount(): Int = downloadedIds().size

    fun saveVersion(materialId: Long, version: Int) {
        prefs.edit().putInt("v_$materialId", version).apply()
    }

    fun version(materialId: Long): Int = prefs.getInt("v_$materialId", 0)

    fun deleteMaterial(materialId: Long) {
        materialFile(materialId).delete()
        prefs.edit().remove("v_$materialId").apply()
    }

    //  Descargas quitadas sin internet (se envían al Actualizar)
    fun pendingRemoved(): Set<String> = prefs.getStringSet("pending_removed", emptySet()) ?: emptySet()

    fun addPendingRemoved(materialId: Long) {
        prefs.edit().putStringSet("pending_removed", pendingRemoved() + materialId.toString()).apply()
    }

    fun clearPendingRemoved() {
        prefs.edit().remove("pending_removed").apply()
    }

    //  Notas de pruebas hechas en el celular
    fun localResult(testId: Long): MyResult? {
        val text = prefs.getString("r_$testId", null) ?: return null
        val parts = text.split("/")
        return if (parts.size == 2) MyResult(parts[0].toInt(), parts[1].toInt()) else null
    }

    fun saveLocalResult(testId: Long, result: MyResult) {
        prefs.edit().putString("r_$testId", "${result.correctas}/${result.total}").apply()
    }

    //  Pruebas resueltas que faltan por enviar al profesor
    fun pendingResults(): List<PendingResult> {
        val json = prefs.getString("pending_results", null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<PendingResult>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addPendingResult(result: PendingResult) {
        val list = pendingResults().filter { it.testId != result.testId } + result
        prefs.edit().putString("pending_results", gson.toJson(list)).apply()
    }

    fun clearPendingResults() {
        prefs.edit().remove("pending_results").apply()
    }

    companion object {
        const val MAX_DOWNLOADS = 10

        // Claves para saber si algo es nuevo (la versión hace que un archivo actualizado vuelva a contar como nuevo)
        fun materialKey(m: SyncMaterial) = "m${m.id}-v${m.version}"
        fun testKey(t: SyncTest) = "p${t.id}"
        fun gameKey(g: SyncGame) = "j${g.id}"
    }
}