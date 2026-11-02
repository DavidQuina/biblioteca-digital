package com.david.biblioteca

import android.os.Build
import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ApiClient {

    //  Dirección pública de ngrok (sin https:// ni / al final)
    //    Con esto la app funciona en cualquier red: la U, la casa o datos móviles.
    private const val PUBLIC_HOST = "transpose-glacial-tamale.ngrok-free.dev"

    private const val PORT = 3000
    private const val EMULATOR_HOST = "10.0.2.2"   // en el emulador, 10.0.2.2 = el portátil

    // Dirección provisional: la app la cambia sola por la del servidor que encuentre
    const val BASE_URL = "http://servidor:3000/"

    /** A dónde se conecta la app: http o https, host y puerto. */
    private data class Target(val scheme: String, val host: String, val port: Int)

    @Volatile
    private var target: Target? = null

    /** ¿La app está corriendo en el emulador de Android Studio? */
    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.contains("sdk_gphone") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.PRODUCT.contains("sdk")

    /** ¿En esta dirección responde nuestro servidor? */
    private fun isServer(url: String, timeoutMs: Int): Boolean = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("ngrok-skip-browser-warning", "true")
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        text.contains("Biblioteca")
    } catch (e: Exception) {
        false
    }

    /** Busca el portátil en la red Wi-Fi actual (plan B si no hay dirección pública). */
    private fun findInNetwork(): String? {
        val candidates = mutableListOf<String>()
        for (network in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp || network.isLoopback) continue
            for (address in Collections.list(network.inetAddresses)) {
                if (address is Inet4Address && address.isSiteLocalAddress) {
                    val myIp = address.hostAddress ?: continue
                    val parts = myIp.split(".")
                    val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
                    for (i in 1..254) {
                        val ip = prefix + i
                        if (ip != myIp) candidates.add(ip)
                    }
                }
            }
        }
        val list = candidates.distinct()
        if (list.isEmpty()) return null

        val pool = Executors.newFixedThreadPool(64)
        try {
            val search = ExecutorCompletionService<String?>(pool)
            list.forEach { ip ->
                search.submit(Callable { if (isServer("http://$ip:$PORT/api/salud", 700)) ip else null })
            }
            repeat(list.size) {
                val found = search.take().get()
                if (found != null) return found
            }
        } finally {
            pool.shutdownNow()
        }
        return null
    }

    /** Decide a dónde conectarse: emulador → portátil; celular → internet (ngrok) o Wi-Fi. */
    @Synchronized
    private fun findTarget(): Target? {
        target?.let { return it }
        val found = when {
            isEmulator() -> Target("http", EMULATOR_HOST, PORT)
            PUBLIC_HOST.isNotBlank() &&
                    isServer("https://$PUBLIC_HOST/api/salud", 6000) -> Target("https", PUBLIC_HOST, 443)
            else -> findInNetwork()?.let { Target("http", it, PORT) }
        }
        target = found
        return found
    }

    private fun withTarget(request: Request, t: Target): Request =
        request.newBuilder()
            .url(request.url.newBuilder().scheme(t.scheme).host(t.host).port(t.port).build())
            .header("ngrok-skip-browser-warning", "true")
            .build()

    private val serverInterceptor = Interceptor { chain ->
        val t = findTarget() ?: throw IOException("No se encontró el servidor. ¿Está encendido iniciar-servidor?")
        try {
            chain.proceed(withTarget(chain.request(), t))
        } catch (e: IOException) {
            // Si dejó de responder, se busca otra vez
            target = null
            val again = findTarget() ?: throw e
            if (again == t) throw e
            chain.proceed(withTarget(chain.request(), again))
        }
    }

    val api: ApiService by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(serverInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    /** Saca el mensaje de error que manda el servidor: {"error": "..."} */
    fun errorMessage(response: Response<*>): String {
        return try {
            val text = response.errorBody()?.string()
            Gson().fromJson(text, ErrorResponse::class.java)?.error ?: "Error del servidor (${response.code()})"
        } catch (e: Exception) {
            "Error del servidor (${response.code()})"
        }
    }
}