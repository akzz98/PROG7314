package za.co.munipulse.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import za.co.munipulse.report.PhotoFiles
import java.io.File

object DeviceNetwork {
    fun isOnline(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (error: Exception) {
            Log.e(TAG, "Network check failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private const val TAG = "MuniPulseSync"
}

object QueuedPhotos {
    fun store(context: Context, mutationId: String, uris: List<Uri>): List<String>? {
        if (!FOLDER_ID.matches(mutationId)) {
            Log.e(TAG, "Queued photo folder was rejected")
            return null
        }
        if (uris.isEmpty()) {
            return emptyList()
        }
        val folder = File(context.filesDir, "queued-photos/$mutationId")
        if (folder.exists() && !folder.deleteRecursively()) {
            Log.e(TAG, "Queued photo folder was not cleared")
            return null
        }
        if (!folder.mkdirs()) {
            Log.e(TAG, "Queued photo folder was not created")
            return null
        }
        val paths = ArrayList<String>(uris.size)
        for ((index, uri) in uris.withIndex()) {
            val bytes = PhotoFiles.jpegBytes(context, uri)
            if (bytes == null) {
                folder.deleteRecursively()
                return null
            }
            val file = File(folder, "$index.jpg")
            try {
                file.writeBytes(bytes)
            } catch (error: Exception) {
                Log.e(TAG, "Queued photo save failed: ${error.javaClass.simpleName}")
                folder.deleteRecursively()
                return null
            }
            paths += file.absolutePath
        }
        Log.i(TAG, "Queued ${paths.size} photos for $mutationId")
        return paths
    }

    private const val TAG = "MuniPulseSync"
    private val FOLDER_ID = Regex("^[A-Za-z0-9-]{1,80}$")
}
