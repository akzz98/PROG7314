package za.co.munipulse.auth

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import za.co.munipulse.BuildConfig

// GET and PATCH /api/v1/me. The bearer token is a header only and is never logged.
// https://square.github.io/retrofit/
object ProfileClient {
    private val api: ProfileApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ProfileApi::class.java)
    }

    suspend fun get(accessToken: String): UserProfileDto = parse(api.me(bearer(accessToken)))

    suspend fun patch(accessToken: String, body: PatchMeBody): UserProfileDto =
        parse(api.patchMe(bearer(accessToken), body))

    private fun bearer(accessToken: String) = "Bearer $accessToken"

    private fun parse(response: Response<UserProfileDto>): UserProfileDto {
        if (!response.isSuccessful) {
            throw ProfileSyncException(messageFor(response))
        }
        return response.body() ?: throw ProfileSyncException("The profile response was empty.")
    }

    private fun messageFor(response: Response<UserProfileDto>): String {
        val body = response.errorBody()?.string().orEmpty()
        val marker = "\"message\":\""
        val start = body.indexOf(marker)
        if (start < 0) {
            return "The API rejected the profile (${response.code()})."
        }
        val from = start + marker.length
        val end = body.indexOf('"', from)
        if (end <= from) {
            return "The API rejected the profile (${response.code()})."
        }
        return body.substring(from, end)
    }
}

private interface ProfileApi {
    @GET("api/v1/me")
    suspend fun me(@Header("Authorization") authorization: String): Response<UserProfileDto>

    @PATCH("api/v1/me")
    suspend fun patchMe(
        @Header("Authorization") authorization: String,
        @Body body: PatchMeBody,
    ): Response<UserProfileDto>
}

data class ApiNotifications(
    val ticketStatus: Boolean = true,
    val areaEmergencies: Boolean = true,
)

data class UserProfileDto(
    val id: String = "",
    val displayName: String? = null,
    val email: String? = null,
    val defaultWardCode: String? = null,
    val preferredLanguage: String? = null,
    val roles: List<String>? = null,
    val notifications: ApiNotifications? = null,
)

data class PatchMeBody(
    val defaultWardCode: String? = null,
    val preferredLanguage: String? = null,
    val notifications: ApiNotifications? = null,
)

class ProfileSyncException(message: String) : IllegalStateException(message)
