package com.qiapp.qi

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.ncnn.SherpaNcnn
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧（手机本地）语音转文本门面，基于 Sherpa-NCNN 的**流式 transducer**（离线、不连云）。
 *
 * 与上游 ZorvAI 的差异：上游把引擎跑在独立进程 `:asr`（Messenger IPC）以隔离原生 SIGSEGV；
 * 本 fork 改为**同进程单线程池**承载（降低构建与维护复杂度），原生层异常仍通过 try/catch +
 * lastError 收敛，主线程不崩。模型由 QuroOnDeviceModelManager 在运行期下载解压到私有目录后部署。
 *
 * 所有失败路径都会写入 [lastError]（人类可读），UI 直接展示；同时尽力写到
 * getExternalFilesDir/stt_asr.log，方便无 adb 时通过手机文件管理器取证。
 */
object QuroOnDeviceAsr {

    private const val TAG = "QuroOnDevice"
    private const val BIND_TIMEOUT_MS = 8000L

    /** 单线程池：SherpaNcnn 原生对象只在此线程创建与调用，避免跨线程触碰导致崩溃。 */
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sherpa-asr") }
    private val lock = Any()

    @Volatile private var recognizer: SherpaNcnn? = null
    @Volatile private var loaded = false

    /** 最近一次失败的人类可读原因；成功时清空。供设置页 / 自检页展示。 */
    @Volatile
    var lastError: String = ""
        private set

    private fun fail(reason: String): Boolean {
        lastError = reason
        Log.e(TAG, reason)
        return false
    }

    /** 按机型 CPU 核数推荐的解码线程数（手机端 2~3 最优）。 */
    private fun recommendedThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 8).let { cores ->
            when {
                cores >= 8 -> 3
                cores >= 4 -> 2
                else -> 1
            }
        }

    /** 已部署模型的目录（若无则返回 null）。 */
    fun getDeployedDir(ctx: Context): String? = QuroOnDeviceModelPrefs.getDeployedDir(ctx)

    /** 是否已部署可用模型（三件套齐全）。 */
    fun isModelAvailable(ctx: Context): Boolean =
        QuroOnDeviceModelManager.getDeployedModelFiles(ctx.applicationContext) != null

    fun isReady(): Boolean = loaded && recognizer?.isValid == true

    /** 是否已加载原生库（构造实例前先查，避免无谓崩溃）。 */
    fun isNativeLoaded(): Boolean = SherpaNcnn.nativeLoaded

    /**
     * 确保端侧引擎已加载（设备校验 + 在单线程池加载模型）。幂等，须在后台协程调用。
     * @return 是否就绪（false = 设备/模型不兼容或加载失败，主进程安全）
     */
    suspend fun ensureLoaded(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (loaded && recognizer?.isValid == true) return@withContext true
            val appCtx = ctx.applicationContext
            diag(appCtx, "ensureLoaded: start")

            if (!SherpaNcnn.nativeLoaded) {
                return@withContext fail("原生库未加载：${SherpaNcnn.nativeLoadError.ifEmpty { "System.loadLibrary 失败" }}。请确认安装包包含 arm64-v8a 的 libsherpa-ncnn-jni.so。")
            }
            if (!AsrDeviceCompat.isSupported(appCtx)) {
                return@withContext fail(AsrDeviceCompat.unsupportedReason(appCtx).ifEmpty { "本机不支持端侧离线识别。" })
            }

            val dir = QuroOnDeviceModelPrefs.getDeployedDir(appCtx)
            if (dir.isNullOrEmpty()) {
                return@withContext fail("还没有下载语音识别模型。请在「语音服务 → 语音识别」下载推荐模型（约 22MB）。")
            }
            if (deployedDirMaxFileBytes(dir) < MIN_VALID_MODEL_BYTES) {
                return@withContext fail("模型文件已损坏（目录内最大文件不足 1MB，多半是下载中断或返回了错误页）。请删除后重新下载。")
            }
            val layout = detectAsrLayout(File(dir))
            when (layout) {
                AsrModelLayout.TRANSDUCER -> { /* 布局合法，继续加载 */ }
                AsrModelLayout.ONNX_LEGACY ->
                    return@withContext fail("已部署的是 ONNX 格式模型，与本机 NCNN 引擎不兼容。请删除后重新下载推荐的流式模型。")
                AsrModelLayout.SENSE_VOICE_LEGACY ->
                    return@withContext fail("已部署的是旧版 SenseVoice 模型，当前引擎不支持（引擎内无对应实现），这也是此前识别一直没反应的原因。请删除后重新下载推荐的流式模型（约 22MB）。")
                AsrModelLayout.NONE ->
                    return@withContext fail("模型目录里没有可用的模型文件。请删除后重新下载。")
            }

            val files = findAsrFiles(File(dir), AsrModelType.STREAMING_TRANSDUCER)
                ?: return@withContext fail("模型文件不完整：encoder / decoder / joiner 的 .param 与 .bin 必须成对存在，且需要 tokens.txt。")
            val cfg = buildRecognizerConfig(files, recommendedThreads())
            diag(appCtx, "ensureLoaded: building SherpaNcnn threads=${cfg.modelConfig.numThreads}")
            val r = runCatching { executor.submit(Callable { SherpaNcnn(null, cfg) }).get() }.getOrNull()
            if (r == null || !r.isValid) {
                return@withContext fail("模型加载失败（引擎未给出原因，多半是模型与引擎不兼容）。请删除后重新下载推荐模型。")
            }
            recognizer?.release()
            recognizer = r
            loaded = r.isValid
            lastError = ""
            diag(appCtx, "ensureLoaded: OK")
            true
        }
    }

    /**
     * 识别一段 16-bit PCM（little-endian, 16kHz, 单声道）音频。须在后台协程调用。
     * @return 识别文本（空串表示未识别到或引擎不可用；不会因原生崩溃而闪退）
     */
    suspend fun recognize(pcm: ByteArray): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val r = recognizer
            if (r == null || !r.isValid) {
                fail("端侧识别引擎未就绪。")
                return@withContext ""
            }
            if (pcm.size < 2) {
                fail("没有采集到音频数据（可能是麦克风被其他应用占用）。")
                return@withContext ""
            }
            try {
                diag(null, "recognize: pcmBytes=${pcm.size}")
                val samples = pcmToFloat(pcm)
                executor.submit {
                    r.acceptWaveform(samples, 16000f)
                    while (r.isReady()) r.decode()
                    r.inputFinished()
                    while (r.isReady()) r.decode()
                }.get()
                val text = r.getText()
                r.reset(true)
                if (text.isNotBlank()) {
                    lastError = ""
                    diag(null, "recognize: text=\"${text.take(60)}\"")
                    text
                } else {
                    fail("没有识别到有效语音。")
                    ""
                }
            } catch (e: Throwable) {
                fail("识别失败：${e.message ?: e.javaClass.simpleName}")
                ""
            }
        }
    }

    /** PCM 16-bit LE 裸流 → [-1,1] float 数组。 */
    private fun pcmToFloat(bytes: ByteArray): FloatArray {
        val shorts = bytes.size / 2
        val out = FloatArray(shorts)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val sb = bb.asShortBuffer()
        for (i in 0 until shorts) out[i] = sb.get(i) / 32768.0f
        return out
    }

    /** 解除引擎（释放原生资源）。 */
    fun release(ctx: Context) {
        synchronized(lock) {
            runCatching { recognizer?.release() }
            recognizer = null
            loaded = false
        }
    }

    // ── 尽力而为的诊断日志（写到应用私有外部存储，无 adb 也能通过文件管理器取证） ──
    private fun diag(ctx: Context?, msg: String) {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp  $msg\n"
        try {
            val dir = ctx?.getExternalFilesDir(null) ?: return
            val log = File(dir, "stt_asr.log")
            log.appendText(line)
        } catch (_: Throwable) {
            // 写日志失败不影响主流程
        }
    }
}
