package za.co.munipulse.report

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

data class MediaAccess(val camera: Boolean, val gallery: Boolean)

// Camera and gallery. Location is handled beside this, on the same report step.
object MediaPermissions {
    fun camera(): String = Manifest.permission.CAMERA

    fun gallery(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun request(): Array<String> = arrayOf(camera(), gallery())

    fun current(context: Context): MediaAccess = MediaAccess(
        camera = isGranted(context, camera()),
        gallery = isGalleryGranted(context),
    )

    fun fromResult(result: Map<String, Boolean>, context: Context): MediaAccess {
        val cameraGranted = result[camera()] == true || isGranted(context, camera())
        val galleryGranted = result[gallery()] == true || isGalleryGranted(context)
        logOutcome("Camera", cameraGranted)
        logOutcome("Gallery", galleryGranted)
        return MediaAccess(cameraGranted, galleryGranted)
    }

    private fun isGalleryGranted(context: Context): Boolean {
        if (isGranted(context, gallery())) {
            return true
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (error: Exception) {
            Log.e(TAG, "Permission check failed: ${error.javaClass.simpleName}")
            false
        }

    private fun logOutcome(name: String, granted: Boolean) {
        Log.i(TAG, "$name permission ${if (granted) "granted" else "denied"}")
    }

    private const val TAG = "MuniPulse"
}
