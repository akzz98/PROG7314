package za.co.munipulse.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object PhotoFiles {
    fun newCaptureUri(context: Context): Uri? {
        return try {
            val directory = File(context.cacheDir, "incident-photos")
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e(TAG, "Photo folder was not created")
                return null
            }
            val file = File(directory, "capture-${System.currentTimeMillis()}.jpg")
            if (!file.createNewFile() && !file.exists()) {
                Log.e(TAG, "Photo file was not created")
                return null
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (error: Exception) {
            Log.e(TAG, "Photo file failed: ${error.javaClass.simpleName}")
            null
        }
    }

    fun thumbnail(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                val sample = sampleSize(bounds.outWidth, bounds.outHeight)
                context.contentResolver.openInputStream(uri)?.use { decoded ->
                    BitmapFactory.decodeStream(
                        decoded,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Photo preview failed: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > TARGET_PX || height / sample > TARGET_PX) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private const val TAG = "MuniPulse"
    private const val TARGET_PX = 480
}
