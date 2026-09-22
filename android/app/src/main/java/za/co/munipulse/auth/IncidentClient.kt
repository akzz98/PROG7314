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
import retrofit2.http.Path
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

    suspend fun listNearby(
        accessToken: String,
        category: String,
        latitude: Double,
        longitude: Double,
        wardCode: String,
    ): List<NearbyDuplicateBody> {
        val response = api.nearby(bearer(accessToken), category, latitude, longitude, wardCode)
        if (!response.isSuccessful) {
            throw incidentError(response)
        }
        return response.body()?.items.orEmpty()
    }

    suspend fun listAggregates(accessToken: String, wardCode: String): List<AggregateBody> {
        val response = api.aggregates(bearer(accessToken), wardCode)
        if (!response.isSuccessful) {
            throw incidentError(response)
        }
        return response.body()?.items.orEmpty()
    }

    suspend fun listWard(accessToken: String, wardCode: String): List<IncidentSummaryBody> =
        list(accessToken, "ward", wardCode, null)

    suspend fun listMine(accessToken: String, status: String?): List<IncidentSummaryBody> =
        list(accessToken, "mine", null, status)

    suspend fun upvote(accessToken: String, incidentId: String): UpvoteOutcome {
        val response = api.upvote(bearer(accessToken), incidentId)
        if (response.code() == 409 && errorCode(response) == "ALREADY_UPVOTED") {
            return UpvoteOutcome.Already
        }
        if (!response.isSuccessful) {
            throw incidentError(response)
        }
        val count = response.body()?.upvoteCount
            ?: throw IncidentRequestException("EMPTY", "The upvote response was empty.")
        return UpvoteOutcome.Counted(count)
    }

    suspend fun detail(accessToken: String, incidentId: String): IncidentDetailBody {
        val response = api.detail(bearer(accessToken), incidentId)
        if (!response.isSuccessful) {
            throw incidentError(response)
        }
        return response.body() ?: throw IncidentRequestException("EMPTY", "The incident response was empty.")
    }

    suspend fun photoJpeg(accessToken: String, photoId: String): ByteArray? {
        if (!PHOTO_ID.matches(photoId)) {
            return null
        }
        val response = api.photo(bearer(accessToken), photoId)
        if (!response.isSuccessful) {
            return null
        }
        val bytes = response.body()?.bytes() ?: return null
        val jpeg = bytes.size in 3..MAX_JPEG &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        return if (jpeg) bytes else null
    }

    private suspend fun list(
        accessToken: String,
        scope: String,
        wardCode: String?,
        status: String?,
    ): List<IncidentSummaryBody> {
        val response = api.list(bearer(accessToken), scope, wardCode, status, PAGE)
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

    private const val PAGE = 50
    private const val MAX_JPEG = 5_000_000
    private val PHOTO_ID = Regex("^[0-9a-fA-F]{32}$")
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

    @GET("api/v1/incidents/nearby")
    suspend fun nearby(
        @Header("Authorization") authorization: String,
        @Query("category") category: String,
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("wardCode") wardCode: String,
    ): Response<NearbyListBody>

    @GET("api/v1/incidents/aggregates")
    suspend fun aggregates(
        @Header("Authorization") authorization: String,
        @Query("wardCode") wardCode: String,
    ): Response<AggregateListBody>

    @GET("api/v1/incidents")
    suspend fun list(
        @Header("Authorization") authorization: String,
        @Query("scope") scope: String,
        @Query("wardCode") wardCode: String?,
        @Query("status") status: String?,
        @Query("limit") limit: Int,
    ): Response<IncidentListBody>

    @POST("api/v1/incidents/{id}/upvotes")
    suspend fun upvote(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): Response<UpvoteBody>

    @GET("api/v1/incidents/{id}")
    suspend fun detail(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): Response<IncidentDetailBody>

    @GET("api/v1/incidents/photos/{id}")
    suspend fun photo(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): Response<okhttp3.ResponseBody>
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

data class NearbyListBody(val items: List<NearbyDuplicateBody> = emptyList())

data class NearbyDuplicateBody(
    val id: String = "",
    val category: String = "",
    val place: String = "",
    val upvoteCount: Int = 0,
    val distanceMeters: Int = 0,
)

data class AggregateListBody(val items: List<AggregateBody> = emptyList())

data class AggregateBody(
    val aggregateId: String = "",
    val category: String = "",
    val reportCount: Int = 0,
    val upvoteCount: Int = 0,
    val incidentId: String = "",
)

data class IncidentSummaryBody(
    val id: String = "",
    val category: String = "",
    val description: String = "",
    val status: String = "",
    val upvoteCount: Int = 0,
    val viewerHasUpvoted: Boolean = false,
    val createdAt: String = "",
)

data class UpvoteBody(val upvoteCount: Int = 0)

sealed interface UpvoteOutcome {
    data class Counted(val upvoteCount: Int) : UpvoteOutcome
    data object Already : UpvoteOutcome
}

data class IncidentDetailBody(
    val id: String = "",
    val category: String = "",
    val description: String = "",
    val status: String = "",
    val upvoteCount: Int = 0,
    val viewerHasUpvoted: Boolean = false,
    val aggregateId: String? = null,
    val photos: List<PhotoLinkBody>? = emptyList(),
    val timeline: List<TimelineEventBody>? = emptyList(),
    val createdAt: String = "",
)

data class PhotoLinkBody(val id: String = "", val url: String? = null)

data class TimelineEventBody(
    val at: String = "",
    val type: String = "",
    val note: String = "",
    val actorRole: String = "",
)

sealed interface IncidentCreateOutcome {
    data class Created(val incidentId: String) : IncidentCreateOutcome
    data object Duplicate : IncidentCreateOutcome
}

class IncidentRequestException(val code: String, message: String) : IllegalStateException(message)
