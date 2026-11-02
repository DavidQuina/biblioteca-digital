package com.david.biblioteca

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File

/** Lo que pasa al tocar "Actualizar". */
class SyncManager(
    private val context: Context,
    private val session: Session,
    private val store: OfflineStore
) {

    class SessionExpired : Exception("Tu sesión venció. Ingresa de nuevo.")

    data class Result(val news: List<String>, val sent: Int, val warnings: List<String>)

    suspend fun sync(): Result {
        val token = "Bearer ${session.token}"
        val warnings = mutableListOf<String>()

        // 1) Enviar al profesor lo que se hizo sin internet
        val pendingResults = store.pendingResults()
        val pendingRemoved = store.pendingRemoved().mapNotNull { it.toLongOrNull() }
        var sent = 0
        if (pendingResults.isNotEmpty() || pendingRemoved.isNotEmpty()) {
            val response = ApiClient.api.send(token, SendRequest(pendingResults, pendingRemoved))
            if (response.code() == 401) throw SessionExpired()
            if (response.isSuccessful) {
                sent = pendingResults.size + pendingRemoved.size
                store.clearPendingResults()
                store.clearPendingRemoved()
            } else {
                warnings += "No se pudo enviar lo pendiente: ${ApiClient.errorMessage(response)}"
            }
        }

        // 2) Bajar lo nuevo del profesor y guardarlo en el celular
        val before = store.loadSync()
        val response = ApiClient.api.sync(token)
        if (response.code() == 401) throw SessionExpired()
        if (!response.isSuccessful) throw Exception(ApiClient.errorMessage(response))
        val json = response.body()!!.string()
        val now = Gson().fromJson(json, SyncData::class.java)
        store.saveSync(json)

        // 3) Novedades (lo que no estaba la vez anterior)
        val news = computeNews(before, now)
        if (news.isNotEmpty() || before == null) store.saveNews(news)

        // 4) Quitar del celular lo que el profesor ocultó o borró
        val available = now.materiales.associateBy { it.id }
        store.downloadedIds().forEach { id ->
            val m = available[id]
            if (m == null || m.descarga != "activa") store.deleteMaterial(id)
        }

        // 5) Bajar la nueva versión de lo que ya tenía
        now.materiales.filter { store.isDownloaded(it.id) && store.version(it.id) < it.version }.forEach { m ->
            try {
                val r = ApiClient.api.download(token, m.id)
                if (r.isSuccessful) {
                    saveFile(r.body()!!, store.materialFile(m.id))
                    store.saveVersion(m.id, r.headers()["X-Version"]?.toIntOrNull() ?: m.version)
                } else {
                    warnings += "No se pudo actualizar \"${m.titulo}\""
                }
            } catch (e: Exception) {
                warnings += "No se pudo actualizar \"${m.titulo}\""
            }
        }

        // 6) Fotos de los juegos del profesor
        now.juegos.flatMap { it.parejas }.mapNotNull { it.archivo }.distinct().forEach { name ->
            val file = File(context.filesDir, "imagenes/$name")
            if (!file.exists()) {
                try {
                    val r = ApiClient.api.gameImage(name)
                    if (r.isSuccessful) saveFile(r.body()!!, file)
                } catch (e: Exception) { /* se intenta la próxima vez */ }
            }
        }

        return Result(news, sent, warnings)
    }

    private fun computeNews(before: SyncData?, now: SyncData): List<String> {
        val news = mutableListOf<String>()
        val oldMaterials = before?.materiales?.associate { it.id to it.version } ?: emptyMap()
        val oldTests = before?.pruebas?.map { it.id }?.toSet() ?: emptySet()
        val oldPartials = before?.parciales?.map { it.id }?.toSet() ?: emptySet()
        val oldGames = before?.juegos?.map { it.id }?.toSet() ?: emptySet()

        now.materiales.forEach { m ->
            when {
                m.id !in oldMaterials -> news += "📖 ${typeName(m.tipo)}: ${m.titulo}"
                (oldMaterials[m.id] ?: 0) < m.version -> news += "🔄 Nueva versión: ${m.titulo}"
            }
        }
        now.pruebas.filter { it.id !in oldTests }.forEach { news += "📝 Prueba: ${it.titulo}" }
        now.parciales.filter { it.id !in oldPartials }.forEach {
            val p = it.fecha.take(10).split("-")
            val date = if (p.size == 3) "${p[2]}/${p[1]}" else it.fecha
            news += "📅 ${it.titulo} el $date"
        }
        now.juegos.filter { it.byTeacher && it.id !in oldGames }.forEach { news += "🎮 Juego: ${it.titulo}" }
        return news
    }

    /** Guarda un archivo (primero en .tmp para no dejarlo a medias). */
    private suspend fun saveFile(body: ResponseBody, destination: File) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".tmp")
        body.byteStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
        if (destination.exists()) destination.delete()
        temp.renameTo(destination)
    }
}

