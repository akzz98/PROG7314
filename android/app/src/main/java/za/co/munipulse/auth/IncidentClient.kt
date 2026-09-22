package za.co.munipulse.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import za.co.munipulse.BuildConfig

// Uploads JPEG parts, then posts the incident. The bearer token is never logged.
object IncidentClient {
    private val api: IncidentApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IncidentApi::class.java)
    }

    suspend fun uploadPhotos(accessToken: String, files: List<ByteArray>): List<String> {
        val parts = files.mapIndexed { index, bytes ->
            MultipartBody.Part.createFormData(
                "files",
                "photo-$index.jpg",
                bytes.toRequestBody("image/jpeg".toMediaType()),
            )
        }
        val response = api.upload(bearer(accessToken), parts)
        if (!response.isSuccessful) {
            throw incidentError(response)
        }
        val ids = response.body()?.photoIds.orEmpty()
        if (ids.size != files.size || ids.any { it.isBlank() }) {
            throw IncidentRequestException("UPLOAD_FAILED", "The photo upload did not return ids.")
        }
        return ids
    }

    suspend fun create(accessToken: String, body: CreateIncidentBody): IncidentCreateOutcome {
        val response = api.create(bearer(accessToken), body)
        if (response.code() == 409 && errorCode(response) == "DUPLICATE_MUTATION") {
            return IncidentCreateOutcome.Duplicate
        }
        if (!response.isSuccessful) {
            throw incidentError(response)
        }
        val created = response.body()
            ?: throw IncidentRequestException("EMPTY", "The incident response was empty.")
        if (created.id.isBlank()) {
            throw IncidentRequestException("EMPTY", "The incident response was empty.")
        }
        return IncidentCreateOutcome.Created(created.id)
    }

    suspend fun listWard(accessToken: String, wardCode: String): List<IncidentSummaryBody> {
        val response = api.list(bearer(accessToken), "ward", wardCode, WARD_PAGE)
        if (!response.isSuccessful) {
            throw incidentError(response)
        }
        return response.body()?.items.orEmpty()
    }

    private fun bearer(accessToken: String) = "Bearer $accessToken"

    private fun <T> incidentError(response: Response<T>): IncidentRequestException {
        val body = response.errorBody()?.string().orEmpty()
        val code = field(body, "code") ?: "HTTP_${response.code()}"
        val message = field(body, "message") ?: "The API rejected the report (${response.code()})."
        return IncidentRequestException(code, message)
    }

    private fun <T> errorCode(response: Response<T>): String? = field(response.errorBody()?.string().orEmpty(), "code")

    private fun field(body: String, name: String): String? {
        val marker = "\"$name\":\""
        val start = body.indexOf(marker)
        if (start < 0) {
            return null
        }
        val from = start + marker.length
        val end = body.indexOf('"', from)
        if (end <= from) {
            return null
        }
        return body.substring(from, end)
    }

    private const val WARD_PAGE = 50
}

private interface IncidentApi {
    @Multipart
    @POST("api/v1/incidents/photos")
    suspend fun upload(
        @Header("Authorization") authorization: String,
        @Part files: List<MultipartBody.Part>,
    ): Response<PhotoUploadResponse>

    @POST("api/v1/incidents")
    suspend fun create(
        @Header("Authorization") authorization: String,
        @Body body: CreateIncidentBody,
    ): Response<CreatedIncidentResponse>

    @GET("api/v1/incidents")
    suspend fun list(
        @Header("Authorization") authorization: String,
        @Query("scope") scope: String,
        @Query("wardCode") wardCode: String,
        @Query("limit") limit: Int,
    ): Response<IncidentListBody>
}

data class PhotoUploadResponse(val photoIds: List<String> = emptyList())

data class CreateIncidentBody(
    val category: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val wardCode: String,
    val photoIds: List<String>,
    val clientMutationId: String,
)

data class CreatedIncidentResponse(val id: String = "")

data class IncidentListBody(val items: List<IncidentSummaryBody> = emptyList())

data class IncidentSummaryBody(
    val id: String = "",
    val category: String = "",
    val description: String = "",
    val status: String = "",
    val upvoteCount: Int = 0,
)

sealed interface IncidentCreateOutcome {
    data class Created(val incidentId: String) : IncidentCreateOutcome
    data object Duplicate : IncidentCreateOutcome
}

class IncidentRequestException(val code: String, message: String) : IllegalStateException(message)
