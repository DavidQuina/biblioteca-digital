package com.david.biblioteca

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.DELETE
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.Path
import okhttp3.ResponseBody
import retrofit2.http.Streaming
// ----- Lo que enviamos -----
data class RegisterRequest(
    val rol: String,
    val nombre: String,
    val correo: String,
    val clave: String,
    val grado: Int? = null,
    val grupo: String? = null
)

data class LoginRequest(
    val correo: String,
    val clave: String,
    val rol: String
)

// Lo que responde el servidor
data class User(
    val id: Long,
    val nombre: String,
    val correo: String,
    val rol: String,
    val grado: Int?,
    val grupo: String?
)

data class AuthResponse(
    val token: String,
    @SerializedName("usuario") val user: User
)

data class ErrorResponse(val error: String?)

//  Profesor: inicio
data class Subject(
    val id: Long,
    val nombre: String,
    val color: String,
    val icono: String
)

data class Recent(
    val clase: String,
    val id: Long,
    val titulo: String,
    val tipo: String,
    @SerializedName("creado_en") val creadoEn: String
)

data class Summary(
    val materiales: Int,
    val pruebas: Int,
    val parciales: Int,
    val juegos: Int,
    val estudiantes: Int,
    val ultimos: List<Recent>
)

// Profesor: material
data class Material(
    val id: Long,
    val titulo: String,
    val tipo: String,
    val version: Int,
    val tamano: Long,
    val visible: Boolean
)

// Profesor: pruebas
data class QuestionRequest(
    val enunciado: String,
    val opciones: List<String>,
    val correcta: Int
)

data class CreateTestRequest(
    val titulo: String,
    @SerializedName("materia_id") val materiaId: Long,
    val grado: Int,
    val grupo: String,
    val preguntas: List<QuestionRequest>
)

data class Test(
    val id: Long,
    val titulo: String
)
// Profesor: parciales
data class MaterialShort(
    val id: Long,
    val titulo: String,
    val tipo: String
)

data class CreatePartialRequest(
    val titulo: String,
    val fecha: String,              // "2026-10-15"
    val aviso: String,
    @SerializedName("materia_id") val materiaId: Long,
    val grado: Int,
    val grupo: String,
    @SerializedName("material_ids") val materialIds: List<Long>
)

data class Partial(
    val id: Long,
    val titulo: String,
    val fecha: String,
    val aviso: String,
    val materiales: List<MaterialShort>

)

// Profesor: mi material
data class VisibleRequest(val visible: Boolean)
// - Estudiante: todo lo que se guarda en el celular -----
data class SyncMaterial(
    val id: Long,
    @SerializedName("materia_id") val subjectId: Long,
    val titulo: String,
    val tipo: String,
    val mime: String,
    val tamano: Long,
    val version: Int,
    @SerializedName("archivo_original") val fileName: String,
    val descarga: String?          // "activa", "quitada" o null
)

data class SyncQuestion(
    val id: Long,
    val enunciado: String,
    val opciones: List<String>,
    val correcta: Int
)

data class MyResult(val correctas: Int, val total: Int)

data class SyncTest(
    val id: Long,
    @SerializedName("materia_id") val subjectId: Long,
    val titulo: String,
    val preguntas: List<SyncQuestion>,
    @SerializedName("mi_resultado") val myResult: MyResult?
)

data class SyncPartial(
    val id: Long,
    @SerializedName("materia_id") val subjectId: Long,
    val titulo: String,
    val fecha: String,
    val aviso: String,
    val materiales: List<MaterialShort>
)

data class GamePair(
    val palabra: String,
    val imagen: String?,
    val archivo: String?
)

data class SyncGame(
    val id: Long,
    @SerializedName("materia_id") val subjectId: Long,
    val tipo: String,
    val titulo: String,
    val parejas: List<GamePair>,
    @SerializedName("de_profesor") val byTeacher: Boolean
)

data class Fact(
    val id: Long,
    @SerializedName("materia_id") val subjectId: Long,
    val texto: String,
    val enlace: String?
)

data class DownloadCounter(val activas: Int, val maximo: Int)

data class SyncData(
    val materias: List<Subject>,
    val materiales: List<SyncMaterial>,
    val pruebas: List<SyncTest>,
    val parciales: List<SyncPartial>,
    val juegos: List<SyncGame>,
    val datos: List<Fact>,
    val descargas: DownloadCounter
)
// Estudiante: enviar lo hecho (con o sin internet)
data class PendingResult(
    @SerializedName("prueba_id") val testId: Long,
    val respuestas: List<Int>,
    @SerializedName("presentada_en") val presentedAt: String
)

data class SendRequest(
    val resultados: List<PendingResult>,
    val quitadas: List<Long>
)

data class SendResponse(
    @SerializedName("descargas_activas") val activeDownloads: Int
)
// Profesor: quién descargó qué
data class DownloadRow(
    @SerializedName("estudiante_id") val studentId: Long,
    val nombre: String,
    val titulo: String?,      // null = aún no descarga nada
    val fecha: String?,
    val estado: String        // "activa", "quitada" o "pendiente"
)

data class DownloadReport(
    val estudiantes: Int,
    @SerializedName("ya_descargaron") val downloaded: Int,
    val filas: List<DownloadRow>
)
//Profesor: resultados
data class TestSummary(
    val id: Long,
    val titulo: String,
    @SerializedName("num_preguntas") val questions: Int,
    val presentaron: Int
)

data class ResultTest(
    val id: Long,
    val titulo: String,
    @SerializedName("total_preguntas") val totalQuestions: Int
)

data class StudentGrade(
    @SerializedName("estudiante_id") val studentId: Long,
    val nombre: String,
    val correctas: Int?,
    val total: Int?,
    val falta: Boolean
)

data class TestResults(
    val prueba: ResultTest,
    @SerializedName("estudiantes_grupo") val groupStudents: Int,
    val presentaron: Int,
    val promedio: Double?,
    @SerializedName("por_estudiante") val students: List<StudentGrade>
)
// Profesor: juegos
data class UploadedImage(val archivo: String, val url: String)

data class PairRequest(
    val palabra: String,
    val imagen: String?,     // emoji o texto
    val archivo: String?     // foto subida
)

data class CreateGameRequest(
    val tipo: String,        // "memoria", "unir" o "reloj"
    val titulo: String,
    @SerializedName("materia_id") val materiaId: Long,
    val grado: Int,
    val grupo: String,
    val parejas: List<PairRequest>
)

data class TeacherGame(
    val id: Long,
    val tipo: String,
    val titulo: String,
    val parejas: List<GamePair>
)

//  Endpoints menú de un restaurante EJ
interface ApiService {
    @POST("api/auth/registro")
    suspend fun register(@Body body: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<AuthResponse>

    @GET("api/materias")
    suspend fun subjects(): Response<List<Subject>>

    @GET("api/profesor/resumen")
    suspend fun summary(
        @Header("Authorization") token: String,
        @Query("materia_id") subjectId: Long,
        @Query("grado") grade: Int,
        @Query("grupo") group: String
    ): Response<Summary>

    @Multipart
    @POST("api/materiales")
    suspend fun uploadMaterial(
        @Header("Authorization") token: String,
        @Part("titulo") title: RequestBody,
        @Part("tipo") type: RequestBody,
        @Part("materia_id") subjectId: RequestBody,
        @Part("grado") grade: RequestBody,
        @Part("grupo") group: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<Material>

    @POST("api/pruebas")
    suspend fun createTest(
        @Header("Authorization") token: String,
        @Body body: CreateTestRequest
    ): Response<Test>
    @GET("api/materiales")
    suspend fun materials(
        @Header("Authorization") token: String,
        @Query("materia_id") subjectId: Long,
        @Query("grado") grade: Int,
        @Query("grupo") group: String
    ): Response<List<Material>>

    @POST("api/parciales")
    suspend fun createPartial(
        @Header("Authorization") token: String,
        @Body body: CreatePartialRequest
    ): Response<Partial>

    @GET("api/parciales")
    suspend fun partials(
        @Header("Authorization") token: String,
        @Query("materia_id") subjectId: Long,
        @Query("grado") grade: Int,
        @Query("grupo") group: String
    ): Response<List<Partial>>
    @PATCH("api/materiales/{id}")
    suspend fun setVisible(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Body body: VisibleRequest
    ): Response<Material>

    @Multipart
    @PUT("api/materiales/{id}/archivo")
    suspend fun updateFile(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Part file: MultipartBody.Part
    ): Response<Material>

    @DELETE("api/materiales/{id}")
    suspend fun deleteMaterial(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<Unit>
    // Devuelve el JSON "crudo" para guardarlo tal cual en el celular
    @GET("api/estudiante/sync")
    suspend fun sync(@Header("Authorization") token: String): Response<ResponseBody>

    // Descargar un material (una sola vez; máximo 10 activas)
    @Streaming
    @GET("api/materiales/{id}/descargar")
    suspend fun download(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<ResponseBody>

    // Quitar una descarga para liberar cupo
    @POST("api/descargas/{id}/quitar")
    suspend fun removeDownload(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<Unit>
    // Envía al profesor las pruebas resueltas y las descargas quitadas
    @POST("api/estudiante/enviar")
    suspend fun send(
        @Header("Authorization") token: String,
        @Body body: SendRequest
    ): Response<SendResponse>
    // Fotos de los juegos del profesor (para usarlas sin internet)
    @Streaming
    @GET("imagenes/{name}")
    suspend fun gameImage(@Path("name") name: String): Response<ResponseBody>
    @GET("api/profesor/descargas")
    suspend fun downloadsReport(
        @Header("Authorization") token: String,
        @Query("grado") grade: Int,
        @Query("grupo") group: String,
        @Query("materia_id") subjectId: Long?     // null = todas las materias
    ): Response<DownloadReport>
    @GET("api/pruebas")
    suspend fun tests(
        @Header("Authorization") token: String,
        @Query("materia_id") subjectId: Long,
        @Query("grado") grade: Int,
        @Query("grupo") group: String
    ): Response<List<TestSummary>>

    @GET("api/pruebas/{id}/resultados")
    suspend fun testResults(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<TestResults>

    @Multipart
    @POST("api/juegos/imagenes")
    suspend fun uploadGameImage(
        @Header("Authorization") token: String,
        @Part image: MultipartBody.Part
    ): Response<UploadedImage>

    @POST("api/juegos")
    suspend fun createGame(
        @Header("Authorization") token: String,
        @Body body: CreateGameRequest
    ): Response<TeacherGame>

    @GET("api/juegos")
    suspend fun teacherGames(
        @Header("Authorization") token: String,
        @Query("materia_id") subjectId: Long,
        @Query("grado") grade: Int,
        @Query("grupo") group: String
    ): Response<List<TeacherGame>>

    @DELETE("api/juegos/{id}")
    suspend fun deleteGame(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): Response<Unit>
}