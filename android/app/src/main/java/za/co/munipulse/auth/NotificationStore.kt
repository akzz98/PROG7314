package za.co.munipulse.auth

import android.content.Context
import android.util.Log

data class NotificationPreferences(
    val ticketStatus: Boolean = true,
    val areaEmergencies: Boolean = true,
    val marketingNews: Boolean = false,
)

// S11 choices on this device. Push delivery is Final POE and is not started here.
class NotificationStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): NotificationPreferences = NotificationPreferences(
        ticketStatus = preferences.getBoolean(KEY_TICKET_STATUS, true),
        areaEmergencies = preferences.getBoolean(KEY_AREA_EMERGENCIES, true),
        marketingNews = preferences.getBoolean(KEY_MARKETING, false),
    )

    fun save(preferences: NotificationPreferences) {
        this.preferences.edit()
            .putBoolean(KEY_TICKET_STATUS, preferences.ticketStatus)
            .putBoolean(KEY_AREA_EMERGENCIES, preferences.areaEmergencies)
            .putBoolean(KEY_MARKETING, preferences.marketingNews)
            .apply()
        Log.i(
            TAG,
            "Saved notification preferences. Ticket status ${preferences.ticketStatus}. " +
                "Area emergencies ${preferences.areaEmergencies}. Marketing ${preferences.marketingNews}.",
        )
    }

    private companion object {
        const val TAG = "MuniPulse"
        const val FILE_NAME = "munipulse_notifications"
        const val KEY_TICKET_STATUS = "ticket_status"
        const val KEY_AREA_EMERGENCIES = "area_emergencies"
        const val KEY_MARKETING = "marketing_news"
    }
}
