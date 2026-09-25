package com.itantra.app.model

/**
 * Detailed specification for a supported language pack in iTantra.
 * Conforms to ISRO Problem Statement 26173 honest storage & model manifest requirements.
 */
data class LanguagePack(
    val code: String,
    val englishName: String,
    val nativeScriptLabel: String,
    val modelId: String,
    val modelUrl: String,
    val tokensUrl: String,
    val tokensFileName: String,
    val modelFileName: String,
    val downloadSizeBytes: Long,
    val installedSizeBytes: Long,
    val expectedChecksumSha256: String,
    val sourceRepository: String,
    val licenseName: String,
    val sampleRateHz: Int = 16000,
    val ttsVoiceCode: String,
    val sampleNativePhrase: String,
    val validationStatus: ValidationStatus
)

enum class ValidationStatus {
    NATIVE_TESTED,
    DEVICE_PROVISIONED,
    CANDIDATE_EXPORT
}

enum class PackDownloadStatus {
    AVAILABLE,
    DOWNLOADING,
    INSTALLED,
    FAILED
}

object LanguageManifest {

    private const val HF_BASE = "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main"
    private const val SHARED_TOKENS_URL = "$HF_BASE/tokens.txt"
    private const val EN_TOKENS_URL = "$HF_BASE/en/tokens.txt"

    /**
     * All 10 official languages specified in ISRO Problem Statement 26173:
     * Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali and English.
     * File sizes directly measured from HuggingFace candidate exports.
     */
    val ALL_LANGUAGES = listOf(
        LanguagePack(
            code = "hi",
            englishName = "Hindi",
            nativeScriptLabel = "हिन्दी",
            modelId = "ai4bharat-indicconformer-ctc-hi-int8",
            modelUrl = "$HF_BASE/hi/model.int8.onnx",
            tokensUrl = SHARED_TOKENS_URL,
            tokensFileName = "tokens.txt",
            modelFileName = "model.int8.onnx",
            downloadSizeBytes = 197595593L, // 188.44 MB
            installedSizeBytes = 197663198L,
            expectedChecksumSha256 = "758b881d2ae5de1142283d08091153eae22e8533",
            sourceRepository = "AI4Bharat IndicConformer / ParisMita Global Solutions",
            licenseName = "Apache-2.0 / MIT",
            sampleRateHz = 16000,
            ttsVoiceCode = "hi",
            sampleNativePhrase = "नमस्ते, यह आई-तंत्रा का संदेश है।",
            validationStatus = ValidationStatus.NATIVE_TESTED
        ),
        LanguagePack(
            code = "en",
            englishName = "English",
            nativeScriptLabel = "English",
            modelId = "nemo-fast-conformer-ctc-en-int8",
            modelUrl = "$HF_BASE/en/model.int8.onnx",
            tokensUrl = EN_TOKENS_URL,
            tokensFileName = "tokens_en.txt",
            modelFileName = "model.int8.onnx",
            downloadSizeBytes = 174610057L, // 166.52 MB
            installedSizeBytes = 174621490L,
            expectedChecksumSha256 = "31379c61b6c558aae6ae31bfaa0c04940cb61a3c",
            sourceRepository = "NVIDIA NeMo / k2-fsa sherpa-onnx",
            licenseName = "CC-BY-4.0",
            sampleRateHz = 16000,
            ttsVoiceCode = "en",
            sampleNativePhrase = "Hello, this is a test transmission from iTantra.",
            validationStatus = ValidationStatus.NATIVE_TESTED
        ),
        LanguagePack(
            code = "gu",
            englishName = "Gujarati",
            nativeScriptLabel = "ગુજરાતી",
            modelId = "ai4bharat-indicconformer-ctc-gu-int8",
            modelUrl = "$HF_BASE/gu/model.int8.onnx",
            tokensUrl = SHARED_TOKENS_URL,
            tokensFileName = "tokens.txt",
            modelFileName = "model.int8.onnx",
            downloadSizeBytes = 197595461L, // 188.44 MB
            installedSizeBytes = 197663066L,
            expectedChecksumSha256 = "67cc77fc5b0f93de0dbaa0504bdf9a927f9f5b65",
            sourceRepository = "AI4Bharat IndicConformer / ParisMita Global Solutions",
            licenseName = "Apache-2.0 / MIT",
            sampleRateHz = 16000,
            ttsVoiceCode = "gu",
            sampleNativePhrase = "નમસ્તે, આ આઇ-તંત્રા તરફથી સંદેશ છે.",
            validationStatus = ValidationStatus.DEVICE_PROVISIONED
        ),
        LanguagePack(
            code = "mr",
            englishName = "Marathi",
            nativeScriptLabel = "मराठी",
            modelId = "ai4bharat-indicconformer-ctc-mr-int8",
            modelUrl = "$HF_BASE/mr/model.int8.onnx",
            tokensUrl = SHARED_TOKENS_URL,
            tokensFileName = "tokens.txt",
            modelFileName = "model.int8.onnx",
            downloadSizeBytes = 197595593L, // 188.44 MB
            installedSizeBytes = 197663198L,
            expectedChecksumSha256 = "a766c5262994c076a2dd2ea16b4a68fa9ff3b234",
            sourceRepository = "AI4Bharat IndicConformer / ParisMita Global Solutions",
            licenseName = "Apache-2.0 / MIT",
            sampleRateHz = 16000,
            ttsVoiceCode = "mr",
            sampleNativePhrase = "नमस्कार, हा आय-तंत्राचा संदेश आहे.",
            validationStatus = ValidationStatus.DEVICE_PROVISIONED
        ),
        LanguagePack(
            code = "kn",
            englishName = "Kannada",
            nativeScriptLabel = "ಕನ್ನಡ",
            modelId = "ai4bharat-indicconformer-ctc-kn-int8",
            modelUrl = "$HF_BASE/kn/model.int8.onnx",
            tokensUrl = SHARED_TOKENS_URL,
            tokensFileName = "tokens.txt",
            modelFileName = "model.int8.onnx",
            downloadSizeBytes = 197595728L, // 188.44 MB
            installedSizeBytes = 197663333L,
            expectedChecksumSha256 = "e1ffe7a53f36b262151a759aaf5ea70a861150f7",
            sourceRepository = "AI4Bharat IndicConformer / ParisMita Global Solutions",
            licenseName = "Apache-2.0 / MIT",
            sampleRateHz = 16000,
            ttsVoiceCode = "kn",
            sampleNativePhrase = "ನಮಸ್ಕಾರ, ಇದು ಐ-ತಂತ್ರದ ಸಂದೇಶವಾಗಿದೆ.",
            validationStatus = ValidationStatus.DEVICE_PROVISIONED
        ),
        LanguagePack(
            code = "ml",
            englishName = "Malayalam",
            nativeScriptLabel = "മലയാളം",
            modelId = "ai4bharat-indicconformer-ctc-ml-int8",
            modelUrl = "$HF_BASE/ml/model.int8.onnx",
            tokensUrl = SHARED_TOKENS_URL,
            tokensFileName = "tokens.txt",
            modelFileName = "model.int8.onnx",
            downloadSizeBytes = 197595555L, // 188.44 MB
            installedSizeBytes = 197663160L,
            expectedChecksumSha256 = "0e04f940b1ed64ad214b04b0ff1c1442532f2e81",
            sourceRepository = "AI4Bharat IndicConformer / ParisMita Global Solutions",
            licenseName = "Apache-2.0 / MIT",
            sampleRateHz = 16000,
            ttsVoiceCode = "ml",
            sampleNativePhrase = "നമസ്കാരം, ഇത് ഐ-തന്ത്ര സന്ദേശമാണ്.",
            validationStatus = ValidationStatus.DEVICE_PROVISIONED
        ),
        LanguagePack(
            code = "ta",
            englishName = "Tamil",
            nativeScriptLabel = "தமிழ்",
            modelId = "ai4bharat-indicconformer-ctc-ta-int8",
            modelUrl = "$HF_BASE/ta/model.int8.onnx",
            tokensUrl = SHARED_TOKENS_URL,
            tokensFileName = "tokens.txt",
            modelFileName = "model.int8.onnx",
            downloadSizeBytes = 197595513L, // 188.44 MB
            installedSizeBytes = 197663118L,
            expectedChecksumSha256 = "f8d980058c423b78d226e4fa6a1e238f8f47889e",
            sourceRepository = "AI4Bharat IndicConformer / ParisMita Global Solutions",
            licenseName = "Apache-2.0 / MIT",
            sampleRateHz = 16000,
            ttsVoiceCode = "ta",
            sampleNativePhrase = "வணக்கம், இது ஐ-தந்திராவின் செய்தி.",
            validationStatus = ValidationStatus.DEVICE_PROVISIONED
        ),
        LanguagePack(
            code = "te",
            englishName = "Telugu",
            nativeScriptLabel = "తెలుగు",
            modelId = "ai4bharat-indicconformer-ctc-te-int8",
            modelUrl = "$HF_BASE/te/model.int8.onnx",
            tokensUrl = SHARED_TOKENS_URL,
            tokensFileName = "tokens.txt",
            modelFileName = "model.int8.onnx",
            downloadSizeBytes = 197595693L, // 188.44 MB
            installedSizeBytes = 197663298L,
            expectedChecksumSha256 = "b28d1790c48923a9f55ab8c2473ab385dab76cf1",
            sourceRepository = "AI4Bharat IndicConformer / ParisMita Global Solutions",
            licenseName = "Apache-2.0 / MIT",
            sampleRateHz = 16000,
            ttsVoiceCode = "te",
            sampleNativePhrase = "నమస్కారం, ఇది ఐ-తంత్ర సందేశం.",
            validationStatus = ValidationStatus.DEVICE_PROVISIONED
        ),
        LanguagePack(
            code = "or",
            englishName = "Odia",
            nativeScriptLabel = "ଓଡ଼ିଆ",
            modelId = "ai4bharat-indicconformer-ctc-or-int8",
            modelUrl = "$HF_BASE/or/model.int8.onnx",
            tokensUrl = SHARED_TOKENS_URL,
            tokensFileName = "tokens.txt",
            modelFileName = "model.int8.onnx",
            downloadSizeBytes = 197584928L, // 188.43 MB
            installedSizeBytes = 197652533L,
            expectedChecksumSha256 = "12eaf99ed0a4233cee40a23fc1c6310343c6d838",
            sourceRepository = "AI4Bharat IndicConformer / ParisMita Global Solutions",
            licenseName = "Apache-2.0 / MIT",
            sampleRateHz = 16000,
            ttsVoiceCode = "or",
            sampleNativePhrase = "ନମସ୍କାର, ଏହା ଆଇ-ତନ୍ତ୍ରର ବାର୍ତ୍ତା।",
            validationStatus = ValidationStatus.DEVICE_PROVISIONED
        ),
        LanguagePack(
            code = "bn",
            englishName = "Bengali",
            nativeScriptLabel = "বাংলা",
            modelId = "ai4bharat-indicconformer-ctc-bn-int8",
            modelUrl = "$HF_BASE/bn/model.int8.onnx",
            tokensUrl = SHARED_TOKENS_URL,
            tokensFileName = "tokens.txt",
            modelFileName = "model.int8.onnx",
            downloadSizeBytes = 197595578L, // 188.44 MB
            installedSizeBytes = 197663183L,
            expectedChecksumSha256 = "ea22259411633f2ff50a889abc290a417292ce53",
            sourceRepository = "AI4Bharat IndicConformer / ParisMita Global Solutions",
            licenseName = "Apache-2.0 / MIT",
            sampleRateHz = 16000,
            ttsVoiceCode = "bn",
            sampleNativePhrase = "নমস্কার, এটি আই-তন্ত্রের বার্তা।",
            validationStatus = ValidationStatus.DEVICE_PROVISIONED
        )
    )

    private val packMap = ALL_LANGUAGES.associateBy { it.code }

    fun getPack(code: String): LanguagePack? = packMap[code.lowercase()]

    fun isValidLanguage(code: String): Boolean = packMap.containsKey(code.lowercase())
}
