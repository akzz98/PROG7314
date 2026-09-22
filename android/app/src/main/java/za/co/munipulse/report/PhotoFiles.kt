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

    fun thumbnail(context: Context, uri: Uri): Bitmap? = decode(context, uri, THUMB_PX, "Photo preview failed")

    fun thumbnail(bytes: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = sampleSize(bounds.outWidth, bounds.outHeight, THUMB_PX)
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (error: Exception) {
            Log.e(TAG, "Photo preview failed: ${error.javaClass.simpleName}")
            null
        }
    }

    fun jpegBytes(context: Context, uri: Uri): ByteArray? {
        val bitmap = decode(context, uri, UPLOAD_PX, "Photo prepare failed") ?: return null
        return try {
            val out = java.io.ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)) {
                Log.e(TAG, "Photo prepare failed: compress")
                return null
            }
            val bytes = out.toByteArray()
            if (bytes.size > MAX_JPEG_BYTES) {
                Log.e(TAG, "Photo was too large to send")
                null
            } else {
                bytes
            }
        } catch (error: Exception) {
            Log.e(TAG, "Photo prepare failed: ${error.javaClass.simpleName}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(context: Context, uri: Uri, targetPx: Int, failure: String): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                val sample = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
                context.contentResolver.openInputStream(uri)?.use { decoded ->
                    BitmapFactory.decodeStream(
                        decoded,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "$failure: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        var sample = 1
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        while (safeWidth / sample > targetPx || safeHeight / sample > targetPx) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private const val TAG = "MuniPulse"
    private const val THUMB_PX = 480
    private const val UPLOAD_PX = 1600
    private const val MAX_JPEG_BYTES = 5_000_000
}
