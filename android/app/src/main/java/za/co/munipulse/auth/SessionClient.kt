package za.co.munipulse.auth

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import za.co.munipulse.BuildConfig

// Exchanges a Firebase ID token for the API JWT from POST /api/v1/auth/session.
// https://square.github.io/retrofit/
object SessionClient {
    private val api: AuthApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApi::class.java)
    }

    suspend fun exchange(firebaseIdToken: String): SessionResponse {
        val response = api.session(SessionRequest(firebaseIdToken))
        if (!response.isSuccessful) {
            throw SessionExchangeException(messageFor(response))
        }
        return response.body() ?: throw SessionExchangeException("The API session response was empty.")
    }

    private fun messageFor(response: Response<SessionResponse>): String {
        val body = response.errorBody()?.string().orEmpty()
        val marker = "\"message\":\""
        val start = body.indexOf(marker)
        if (start < 0) {
            return "The API rejected the session (${response.code()})."
        }
        val from = start + marker.length
        val end = body.indexOf('"', from)
        if (end <= from) {
            return "The API rejected the session (${response.code()})."
        }
        return body.substring(from, end)
    }
}

private interface AuthApi {
    @POST("api/v1/auth/session")
    suspend fun session(@Body body: SessionRequest): Response<SessionResponse>
}

data class SessionRequest(val firebaseIdToken: String)

data class SessionResponse(val accessToken: String, val expiresIn: Int, val user: SessionUser)

data class SessionUser(
    val id: String,
    val displayName: String? = null,
    val email: String? = null,
    val defaultWardCode: String? = null,
    val preferredLanguage: String? = null,
    val roles: List<String>? = null,
    val notifications: ApiNotifications? = null,
)

class SessionExchangeException(message: String) : IllegalStateException(message)
