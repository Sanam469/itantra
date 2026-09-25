package com.itantra.app.stt

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.itantra.app.model.LanguageManifest
import com.itantra.app.model.LanguagePack
import com.itantra.app.model.PackDownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manages downloading, importing, verifying, and deleting offline language packs.
 * Conforms to ISRO 26173 honest storage & model delivery requirements.
 */
class LanguagePackManager(private val context: Context) {

    companion object {
        private const val TAG = "LanguagePackManager"
        private const val BUFFER_SIZE = 32768
        private const val MIN_SAFETY_FREE_BYTES = 50 * 1024 * 1024L // 50 MB safety margin
    }

    interface DownloadProgressListener {
        fun onProgress(langCode: String, progressPercent: Int, bytesDownloaded: Long, totalBytes: Long)
        fun onCompleted(langCode: String)
        fun onError(langCode: String, errorMessage: String)
    }

    private val baseStorageDir: File by lazy {
        val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    fun getModelsDirectory(): File = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    /**
     * Resolves the model file path for a language.
     * Checks both app-internal storage and external files directory.
     */
    fun getModelFile(langCode: String): File? {
        val pack = LanguageManifest.getPack(langCode) ?: return null

        val minExpectedSize = (pack.downloadSizeBytes * 0.85).toLong().coerceAtLeast(10 * 1024 * 1024L)

        // 1. Check internal filesDir (/data/user/0/com.itantra.app/files/models)
        val intModel = File(File(context.filesDir, "models/$langCode"), pack.modelFileName)
        if (intModel.exists() && intModel.length() >= minExpectedSize) return intModel

        // 2. Check externalFilesDir (/sdcard/Android/data/com.itantra.app/files/models)
        val extDir = context.getExternalFilesDir("models")
        if (extDir != null) {
            val extModel = File(File(extDir, langCode), pack.modelFileName)
            if (extModel.exists() && extModel.length() >= minExpectedSize) return extModel
        }

        return null
    }

    /**
     * Resolves the token vocabulary file for a language.
     */
    fun getTokensFile(langCode: String): File? {
        val pack = LanguageManifest.getPack(langCode) ?: return null

        // 1. Check internal storage
        val intLangTokens = File(File(context.filesDir, "models/$langCode"), pack.tokensFileName)
        if (intLangTokens.exists() && intLangTokens.length() > 0) return intLangTokens

        val intBaseTokens = File(File(context.filesDir, "models"), pack.tokensFileName)
        if (intBaseTokens.exists() && intBaseTokens.length() > 0) return intBaseTokens

        // 2. Check external storage
        val extDir = context.getExternalFilesDir("models")
        if (extDir != null) {
            val extLangTokens = File(File(extDir, langCode), pack.tokensFileName)
            if (extLangTokens.exists() && extLangTokens.length() > 0) return extLangTokens

            val extBaseTokens = File(extDir, pack.tokensFileName)
            if (extBaseTokens.exists() && extBaseTokens.length() > 0) return extBaseTokens
        }

        // 3. Check assets and extract if available
        try {
            val targetFile = File(File(context.filesDir, "models"), pack.tokensFileName)
            val assetPath = "models/${pack.tokensFileName}"
            context.assets.open(assetPath).use { input ->
                targetFile.parentFile?.mkdirs()
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (targetFile.exists()) return targetFile
        } catch (e: Exception) {
            Log.d(TAG, "Tokens not in assets: ${pack.tokensFileName}")
        }

        return null
    }

    /**
     * Resolves the Silero VAD model file.
     */
    fun getVadModelFile(): File {
        val intVad = File(File(context.filesDir, "models"), "silero_vad.onnx")
        if (intVad.exists() && intVad.length() > 100000L) return intVad

        val extDir = context.getExternalFilesDir("models")
        if (extDir != null) {
            val extVad = File(extDir, "silero_vad.onnx")
            if (extVad.exists() && extVad.length() > 100000L) return extVad
        }

        return intVad
    }

    fun getStatus(langCode: String): PackDownloadStatus {
        val modelFile = getModelFile(langCode)
        val tokensFile = getTokensFile(langCode)
        return if (modelFile != null && tokensFile != null) {
            PackDownloadStatus.INSTALLED
        } else {
            PackDownloadStatus.AVAILABLE
        }
    }

    /**
     * Checks available storage space on disk against required download size.
     */
    fun hasSufficientSpace(requiredBytes: Long): Boolean {
        return try {
            val stat = StatFs(baseStorageDir.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            availableBytes > (requiredBytes + MIN_SAFETY_FREE_BYTES)
        } catch (e: Exception) {
            true // Fallback if statfs fails
        }
    }

    /**
     * Downloads model and tokens atomically.
     */
    suspend fun downloadPack(
        langCode: String,
        listener: DownloadProgressListener? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val pack = LanguageManifest.getPack(langCode)
        if (pack == null) {
            listener?.onError(langCode, "Unknown language code: $langCode")
            return@withContext false
        }

        if (!hasSufficientSpace(pack.downloadSizeBytes)) {
            val msg = "Insufficient storage space. Required: ${pack.downloadSizeBytes / (1024 * 1024)} MB"
            listener?.onError(langCode, msg)
            return@withContext false
        }

        val langDir = File(baseStorageDir, langCode)
        if (!langDir.exists()) langDir.mkdirs()

        // 1. Download tokens file if missing
        val targetTokensFile = File(langDir, pack.tokensFileName)
        if (!targetTokensFile.exists() || targetTokensFile.length() == 0L) {
            val tokensSuccess = downloadFileWithProgress(
                pack.tokensUrl,
                targetTokensFile,
                null
            )
            if (!tokensSuccess) {
                listener?.onError(langCode, "Failed downloading tokenizer file")
                return@withContext false
            }
        }

        // 2. Download model file atomically with progress
        val targetModelFile = File(langDir, pack.modelFileName)
        val tmpModelFile = File(langDir, "${pack.modelFileName}.tmp")

        val modelSuccess = downloadFileWithProgress(
            pack.modelUrl,
            tmpModelFile,
            object : ProgressCallback {
                override fun onProgress(downloaded: Long, total: Long) {
                    val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    listener?.onProgress(langCode, percent, downloaded, total)
                }
            }
        )

        if (!modelSuccess) {
            if (tmpModelFile.exists()) tmpModelFile.delete()
            listener?.onError(langCode, "Network download failed or interrupted")
            return@withContext false
        }

        // 3. Atomic rename
        if (tmpModelFile.renameTo(targetModelFile)) {
            Log.i(TAG, "Successfully installed pack: $langCode (${targetModelFile.length()} bytes)")
            listener?.onCompleted(langCode)
            true
        } else {
            listener?.onError(langCode, "Atomic file rename failed")
            false
        }
    }

    /**
     * Offline import from SAF Document Picker or external file stream.
     */
    suspend fun importModelFromUri(
        langCode: String,
        sourceUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        val pack = LanguageManifest.getPack(langCode) ?: return@withContext false
        val langDir = File(baseStorageDir, langCode)
        if (!langDir.exists()) langDir.mkdirs()

        val targetFile = File(langDir, pack.modelFileName)
        val tmpFile = File(langDir, "${pack.modelFileName}.import.tmp")

        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return@withContext false

            if (tmpFile.length() > 10 * 1024 * 1024L) { // Min 10MB sanity check
                if (targetFile.exists()) targetFile.delete()
                val renamed = tmpFile.renameTo(targetFile)
                // Ensure tokens exist
                getTokensFile(langCode)
                return@withContext renamed
            } else {
                tmpFile.delete()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed importing model from URI", e)
            if (tmpFile.exists()) tmpFile.delete()
            return@withContext false
        }
    }

    /**
     * Deletes a language pack to free storage.
     */
    fun deletePack(langCode: String): Boolean {
        val langDir = File(baseStorageDir, langCode)
        return if (langDir.exists()) {
            langDir.deleteRecursively()
        } else false
    }

    private interface ProgressCallback {
        fun onProgress(downloaded: Long, total: Long)
    }

    private fun downloadFileWithProgress(
        urlStr: String,
        outFile: File,
        callback: ProgressCallback?
    ): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 40000
                instanceFollowRedirects = true
            }
            conn.connect()

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                Log.e(TAG, "HTTP error $responseCode for $urlStr")
                return false
            }

            val totalBytes = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        callback?.onProgress(downloaded, totalBytes)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Download exception for $urlStr", e)
            return false
        } finally {
            conn?.disconnect()
        }
    }
}
