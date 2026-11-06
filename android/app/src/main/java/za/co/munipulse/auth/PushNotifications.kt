package za.co.munipulse.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import za.co.munipulse.MainActivity
import za.co.munipulse.R

object PushNotifications {
    const val EXTRA_TYPE = "push_type"
    const val EXTRA_INCIDENT_ID = "incident_id"
    const val EXTRA_WARD_CODE = "ward_code"
    const val TYPE_TICKET = "ticket"
    const val TYPE_AREA = "area"

    fun show(
        context: Context,
        title: String,
        body: String,
        type: String,
        incidentId: String?,
        wardCode: String?,
    ) {
        ensureChannel(context)
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TYPE, type)
            if (!incidentId.isNullOrBlank()) {
                putExtra(EXTRA_INCIDENT_ID, incidentId)
            }
            if (!wardCode.isNullOrBlank()) {
                putExtra(EXTRA_WARD_CODE, wardCode)
            }
        }
        val pending = PendingIntent.getActivity(
            context,
            (type + (incidentId ?: wardCode).orEmpty()).hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(type, incidentId, wardCode), notification)
            Log.i(TAG, "Push shown. Type $type")
        } catch (error: SecurityException) {
            Log.e(TAG, "Push display blocked: ${error.javaClass.simpleName}")
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.push_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    private fun notificationId(type: String, incidentId: String?, wardCode: String?): Int =
        (type + (incidentId ?: "") + (wardCode ?: "")).hashCode()

    private const val CHANNEL_ID = "munipulse_alerts"
    private const val TAG = "MuniPulsePush"
}
