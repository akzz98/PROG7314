package za.co.munipulse.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import za.co.munipulse.BuildConfig

// Asks the API for a short-lived upload link, then puts the JPEG on that link.
// The bearer token and the link are never logged.
object IncidentClient {
    private val http = OkHttpClient.Builder().build()

    private val api: IncidentApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IncidentApi::class.java)
    }

    suspend fun uploadPhotos(accessToken: String, files: List<ByteArray>): List<String> {
        val ids = ArrayList<String>(files.size)
        for (bytes in files) {
            if (bytes.size !in 3..MAX_JPEG || !isJpeg(bytes)) {
                throw IncidentRequestException("UPLOAD_FAILED", "The photo was not a JPEG.")
            }
            val response = api.mediaSas(
                bearer(accessToken),
                MediaSasBody(contentType = "image/jpeg", byteSize = bytes.size.toLong()),
            )
            if (!response.isSuccessful) {
                throw incidentError(response)
            }
            val lease = response.body()
                ?: throw IncidentRequestException("UPLOAD_FAILED", "The photo upload did not return an id.")
            if (!PHOTO_ID.matches(lease.photoId) || !lease.uploadUrl.startsWith("https://")) {
                throw IncidentRequestException("UPLOAD_FAILED", "The photo upload did not return an id.")
            }
            val status = try {
                putBlob(lease.uploadUrl, bytes)
            } catch (error: Exception) {
                Log.e(PHOTO_TAG, "Photo upload ${lease.photoId} failed: ${error.javaClass.simpleName}")
                throw IncidentRequestException("UPLOAD_FAILED", "The photo upload was rejected.")
            }
            Log.i(PHOTO_TAG, "Photo upload ${lease.photoId} status $status")
            if (status !in 200..299) {
                throw IncidentRequestException("UPLOAD_FAILED", "The photo upload was rejected.")
            }
            ids += lease.photoId
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

    suspend fun push(accessToken: String, body: SyncPushBody): List<SyncPushResultBody> {
        val response = api.push(bearer(accessToken), body)
        if (!response.isSuccessful) {
            throw incidentError(response)
        }
        return response.body()?.results.orEmpty()
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

    suspend fun photoJpeg(url: String): ByteArray? {
        if (!url.startsWith("https://")) {
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).get().build()
                http.newCall(request).execute().use { response ->
                    Log.i(PHOTO_TAG, "Photo read status ${response.code}")
                    if (!response.isSuccessful) {
                        return@withContext null
                    }
                    val bytes = response.body?.bytes() ?: return@withContext null
                    if (isJpeg(bytes) && bytes.size <= MAX_JPEG) bytes else null
                }
            }
        } catch (error: Exception) {
            Log.e(PHOTO_TAG, "Photo read failed: ${error.javaClass.simpleName}")
            null
        }
    }

    private suspend fun putBlob(uploadUrl: String, bytes: ByteArray): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(uploadUrl)
            .put(bytes.toRequestBody("image/jpeg".toMediaType()))
            .header("x-ms-blob-type", "BlockBlob")
            .build()
        http.newCall(request).execute().use { response -> response.code }
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

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
    private const val PHOTO_TAG = "MuniPulsePhotos"
    private val PHOTO_ID = Regex("^[0-9a-fA-F]{32}$")
}

private interface IncidentApi {
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

    @POST("api/v1/media/sas")
    suspend fun mediaSas(
        @Header("Authorization") authorization: String,
        @Body body: MediaSasBody,
    ): Response<MediaSasResponse>

    @POST("api/v1/sync/push")
    suspend fun push(
        @Header("Authorization") authorization: String,
        @Body body: SyncPushBody,
    ): Response<SyncPushResponse>
}

data class MediaSasBody(val contentType: String, val byteSize: Long)

data class MediaSasResponse(
    val photoId: String = "",
    val uploadUrl: String = "",
    val expiresAt: String = "",
)

data class SyncPushBody(val operations: List<SyncOperationBody>)

data class SyncOperationBody(
    val clientMutationId: String,
    val mutationType: String,
    val category: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val wardCode: String,
    val photoIds: List<String>,
)

data class SyncPushResponse(val results: List<SyncPushResultBody> = emptyList())

data class SyncPushResultBody(
    val clientMutationId: String = "",
    val result: String = "",
    val incidentId: String? = null,
    val code: String? = null,
)

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
