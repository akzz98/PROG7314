package za.co.munipulse.auth

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.POST
import za.co.munipulse.BuildConfig

// Registers or clears the FCM token. The token value is never logged.
object PushClient {
    private val api: PushApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PushApi::class.java)
    }

    suspend fun register(accessToken: String, token: String) {
        val response = api.register(bearer(accessToken), FcmTokenBody(token = token, platform = "android"))
        if (!response.isSuccessful) {
            throw PushRequestException(response.code())
        }
    }

    suspend fun clear(accessToken: String) {
        val response = api.clear(bearer(accessToken))
        if (!response.isSuccessful) {
            throw PushRequestException(response.code())
        }
    }

    private fun bearer(accessToken: String) = "Bearer $accessToken"
}

private interface PushApi {
    @POST("api/v1/devices/fcm-token")
    suspend fun register(
        @Header("Authorization") authorization: String,
        @Body body: FcmTokenBody,
    ): Response<Unit>

    @DELETE("api/v1/devices/fcm-token")
    suspend fun clear(
        @Header("Authorization") authorization: String,
    ): Response<Unit>
}

data class FcmTokenBody(val token: String, val platform: String)

class PushRequestException(val status: Int) : Exception("Push request failed ($status)")
