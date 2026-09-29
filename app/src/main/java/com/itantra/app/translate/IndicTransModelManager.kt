package com.itantra.app.translate

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Manages storage, file validation, and on-demand download of AI4Bharat IndicTrans2 ONNX models.
 */
class IndicTransModelManager(private val context: Context) {

    companion object {
        private const val TAG = "IndicTransModelMgr"

        const val DIR_INDIC_EN = "indic-en"
        const val DIR_EN_INDIC = "en-indic"

        private const val HF_BASE_INDIC_EN =
            "https://huggingface.co/hari31416/indictrans2-indic-en-dist-200M-ONNX-int8/resolve/main"
        private const val HF_BASE_EN_INDIC =
            "https://huggingface.co/hari31416/indictrans2-en-indic-dist-200M-ONNX-int8/resolve/main"

        val REQUIRED_FILES = listOf(
            "encoder_model.onnx",
            "encoder_model.onnx.data",
            "decoder_model.onnx",
            "decoder_shared.onnx.data",
            "decoder_with_past_model.onnx",
            "dict.SRC.json",
            "dict.TGT.json"
        )
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val downloadingSet = ConcurrentHashMap.newKeySet<String>()

    fun getBaseDir(): File {
        val dir = File(context.filesDir, "models/translation")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelDir(direction: String): File {
        val dir = File(getBaseDir(), direction)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Checks if all required ONNX and vocab files exist and are non-empty.
     */
    fun isModelInstalled(direction: String): Boolean {
        val dir = getModelDir(direction)
        for (fileName in REQUIRED_FILES) {
            val file = File(dir, fileName)
            if (!file.exists() || file.length() == 0L) {
                return false
            }
        }
        return true
    }

    /**
     * Returns total disk space used by the model in bytes.
     */
    fun getInstalledSizeBytes(direction: String): Long {
        val dir = getModelDir(direction)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    /**
     * Downloads model files from HuggingFace in background if not already present.
     */
    fun ensureModelDownloaded(
        direction: String,
        onProgress: ((progress: Float) -> Unit)? = null,
        onComplete: (Boolean) -> Unit
    ) {
        if (isModelInstalled(direction)) {
            onComplete(true)
            return
        }

        if (!downloadingSet.add(direction)) {
            Log.i(TAG, "Model $direction is already downloading...")
            return
        }

        executor.execute {
            var success = true
            val dir = getModelDir(direction)
            val baseUrl = if (direction == DIR_INDIC_EN) HF_BASE_INDIC_EN else HF_BASE_EN_INDIC

            try {
                val totalFiles = REQUIRED_FILES.size
                for ((index, fileName) in REQUIRED_FILES.withIndex()) {
                    val destFile = File(dir, fileName)
                    if (destFile.exists() && destFile.length() > 0L) {
                        Log.d(TAG, "File $fileName already exists (${destFile.length()} bytes)")
                        continue
                    }

                    val fileUrl = "$baseUrl/$fileName"
                    Log.i(TAG, "Downloading [$direction] $fileName from $fileUrl")
                    val fileDownloaded = downloadFile(fileUrl, destFile)
                    if (!fileDownloaded) {
                        success = false
                        Log.e(TAG, "Failed downloading $fileName for $direction")
                        break
                    }

                    val progress = (index + 1).toFloat() / totalFiles
                    onProgress?.invoke(progress)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading model for $direction", e)
                success = false
            } finally {
                downloadingSet.remove(direction)
                onComplete(success && isModelInstalled(direction))
            }
        }
    }

    private fun downloadFile(urlStr: String, destFile: File): Boolean {
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        var conn: HttpURLConnection? = null
        try {
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "iTantra-Neural-Transceiver/1.0")
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    currentUrl = conn.getHeaderField("Location") ?: break
                    redirects++
                } else {
                    break
                }
            }

            if (conn!!.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP ${conn.responseCode} downloading $urlStr")
                return false
            }

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }

            if (tempFile.exists() && tempFile.length() > 0L) {
                if (destFile.exists()) destFile.delete()
                return tempFile.renameTo(destFile)
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading $urlStr: ${e.message}")
            if (tempFile.exists()) tempFile.delete()
            return false
        } finally {
            try { conn?.disconnect() } catch (ignored: Exception) {}
        }
    }
}
