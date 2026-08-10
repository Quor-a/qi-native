package com.qiapp.qi

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 权限型工具集（移植自上游 ZorvAI 的 ToolsLocation / ToolsComms / ToolsMedia /
 * ToolsSystem / ToolsCalendar，去品牌化并适配本工程的 [ToolEngine] 单文件架构）。
 *
 * 修复的真实缺陷：
 * 「权限页里一堆权限都已授权，但 AI 说用不了 / 根本不调用」。
 * 根因不是权限网关判错，而是 **fork 的 ToolEngine 只接了 4 个权限工具**
 * （拨号 / 发短信 / 查联系人 / 读日历）。用户在权限页授予的位置、相机、蓝牙、
 * 媒体库、通话记录、短信读取、日历写入等，**模型侧根本没有对应的 function 声明**，
 * 于是「权限给了却调不动」。这里把这些能力逐一补成真实工具。
 *
 * 同时对齐上游两条关键教训（上游 #766 / #768）：
 *  1. **按 API 版本声明权限**：API 33+ 的 READ_EXTERNAL_STORAGE、API 31+ 的 legacy
 *     BLUETOOTH 均恒为 DENIED，若把它们并列写进所需权限，会让「全部满足」判定永远
 *     为 false —— 即便用户已授予现代权限，工具仍被误拒。
 *  2. **多权限工具用「任一满足」**：例如定位的 FINE / COARSE，只授予其一也应放行，
 *     参见 [PermissionGate.ensureAnyGranted]。
 */
object ToolsPerm {

    // ---------- spec 构造helper（与 ToolEngine.fn 同风格，额外支持类型标注） ----------

    private fun spec(
        name: String,
        desc: String,
        fields: List<Triple<String, String, String>> = emptyList(), // name, type, description
        required: List<String> = emptyList(),
    ): JSONObject {
        val props = JSONObject()
        fields.forEach { (n, t, d) -> props.put(n, JSONObject().put("type", t).put("description", d)) }
        val params = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", JSONArray(required))
        return JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", name).put("description", desc).put("parameters", params),
        )
    }

    private fun granted(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    // ---------- 权限声明表（供 ToolEngine.permsFor 复用；语义为「任一满足即可」） ----------

    const val GET_LOCATION = "get_location"
    const val GEOCODE = "geocode"
    const val READ_SMS = "read_sms"
    const val READ_CALL_LOG = "read_call_log"
    const val LIST_MEDIA = "list_media"
    const val TOGGLE_FLASHLIGHT = "toggle_flashlight"
    const val BLUETOOTH_STATUS = "get_bluetooth_status"
    const val WRITE_CALENDAR = "write_calendar"
    const val PHONE_INFO = "get_phone_info"
    const val CHECK_PERMISSIONS = "check_permissions"

    /** 定位：FINE 或 COARSE 任一即可。 */
    private val LOCATION_PERMS = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * 媒体库：API 33+ 只声明现代媒体权限。
     * 旧的 READ_EXTERNAL_STORAGE 在 33+ 上不可授予且恒 DENIED，混进来会造成误拒。
     */
    private val MEDIA_PERMS: List<String>
        get() = if (Build.VERSION.SDK_INT >= 33) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** 蓝牙：API 31+ 用 BLUETOOTH_CONNECT，30- 用 legacy BLUETOOTH。 */
    private val BLUETOOTH_PERMS: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH)
        }

    /** 本机信息：READ_PHONE_STATE 或 READ_PHONE_NUMBERS 任一即可。 */
    private val PHONE_PERMS = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
    )

    /** 工具名 → 所需危险权限（任一满足即放行）。未列出的工具零权限。 */
    fun permsFor(name: String): List<String> = when (name) {
        GET_LOCATION, GEOCODE -> LOCATION_PERMS
        READ_SMS -> listOf(Manifest.permission.READ_SMS)
        READ_CALL_LOG -> listOf(Manifest.permission.READ_CALL_LOG)
        LIST_MEDIA -> MEDIA_PERMS
        TOGGLE_FLASHLIGHT -> listOf(Manifest.permission.CAMERA)
        BLUETOOTH_STATUS -> BLUETOOTH_PERMS
        WRITE_CALENDAR -> listOf(Manifest.permission.WRITE_CALENDAR)
        PHONE_INFO -> PHONE_PERMS
        else -> emptyList()
    }

    /** 本工具集全部工具的 function 声明，追加进 [ToolEngine.spec]。 */
    fun specs(): List<JSONObject> = listOf(
        spec(
            GET_LOCATION,
            "获取设备当前位置（经纬度与精度）。用于回答「我在哪」「当前位置」「附近」等问题。需要定位权限。",
        ),
        spec(
            GEOCODE,
            "地址与坐标互转：给 query 是地址转坐标；给 lat+lng 是坐标转地址。",
            listOf(
                Triple("query", "string", "地址文本，例如 \"北京市天安门\"（正向查询）"),
                Triple("lat", "string", "纬度（反向查询，与 lng 同时给出）"),
                Triple("lng", "string", "经度（反向查询，与 lat 同时给出）"),
            ),
        ),
        spec(
            READ_SMS,
            "读取收件箱里最近的短信（发件人+正文+时间）。用于「我最近收到什么短信」「验证码是多少」等。需要短信权限。",
            listOf(Triple("limit", "string", "返回条数，默认 20，最多 100")),
        ),
        spec(
            READ_CALL_LOG,
            "读取最近的通话记录（号码+类型+时长+时间）。用于「最近谁给我打过电话」等。需要通话记录权限。",
            listOf(Triple("limit", "string", "返回条数，默认 20，最多 100")),
        ),
        spec(
            LIST_MEDIA,
            "列出手机媒体库里的图片 / 视频 / 音频（名称+大小+时间）。用于「我手机里有哪些照片」等。需要媒体或存储权限。",
            listOf(
                Triple("kind", "string", "image / video / audio，默认 image"),
                Triple("limit", "string", "返回条数，默认 20，最多 100"),
            ),
        ),
        spec(
            TOGGLE_FLASHLIGHT,
            "打开或关闭手电筒（闪光灯）。需要相机权限。",
            listOf(Triple("on", "string", "true 开灯 / false 关灯")),
            listOf("on"),
        ),
        spec(
            BLUETOOTH_STATUS,
            "查询蓝牙开关状态与已配对设备列表。需要蓝牙权限。",
        ),
        spec(
            WRITE_CALENDAR,
            "在系统日历中创建一个日程事件。用于「帮我加个日程」「明天下午三点提醒开会（写进日历）」等。需要日历写入权限。",
            listOf(
                Triple("title", "string", "事件标题"),
                Triple("start", "string", "开始时间，格式 \"yyyy-MM-dd HH:mm\" 或毫秒时间戳"),
                Triple("end", "string", "结束时间，同格式；不给则默认开始后 1 小时"),
                Triple("location", "string", "地点（可选）"),
                Triple("description", "string", "备注（可选）"),
            ),
            listOf("title", "start"),
        ),
        spec(
            PHONE_INFO,
            "获取本机电话信息（运营商、网络制式、本机号码等）。需要电话权限。",
        ),
        spec(
            CHECK_PERMISSIONS,
            "自检本应用各项手机权限的真实授权状态（定位/短信/联系人/日历/相机/蓝牙/媒体/通话记录/电话）。" +
                "当用户抱怨「权限给了但你用不了」时，先用它确认到底缺哪一项。零权限即可调用。",
        ),
    )

    /** 是否是本工具集处理的工具名。 */
    fun handles(name: String): Boolean = name in setOf(
        GET_LOCATION, GEOCODE, READ_SMS, READ_CALL_LOG, LIST_MEDIA,
        TOGGLE_FLASHLIGHT, BLUETOOTH_STATUS, WRITE_CALENDAR, PHONE_INFO, CHECK_PERMISSIONS,
    )

    // ---------- 执行 ----------

    fun run(ctx: Context, name: String, args: JSONObject): String = when (name) {
        GET_LOCATION -> getLocation(ctx)
        GEOCODE -> geocode(ctx, args)
        READ_SMS -> readSms(ctx, args)
        READ_CALL_LOG -> readCallLog(ctx, args)
        LIST_MEDIA -> listMedia(ctx, args)
        TOGGLE_FLASHLIGHT -> toggleFlashlight(ctx, args)
        BLUETOOTH_STATUS -> bluetoothStatus(ctx)
        WRITE_CALENDAR -> writeCalendar(ctx, args)
        PHONE_INFO -> phoneInfo(ctx)
        CHECK_PERMISSIONS -> checkPermissions(ctx)
        else -> "未知工具：$name"
    }

    private fun limitOf(args: JSONObject, def: Int = 20): Int =
        (args.optString("limit", "").toIntOrNull() ?: args.optInt("limit", def)).coerceIn(1, 100)

    // ---------- 定位 ----------

    private fun getLocation(ctx: Context): String {
        val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return "未授予定位权限，请在「权限」页授予位置权限后重试。"
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "该设备不支持定位服务"
        return try {
            var loc: Location? = null
            for (p in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )) {
                if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) {
                    loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: loc
                }
            }
            if (loc == null) {
                val provider = if (runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }
                        .getOrDefault(false) && fine
                ) {
                    LocationManager.GPS_PROVIDER
                } else {
                    LocationManager.NETWORK_PROVIDER
                }
                val latch = CountDownLatch(1)
                val holder = arrayOfNulls<Location>(1)
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) {
                        holder[0] = l
                        latch.countDown()
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                // 单次定位必须在有 Looper 的线程上注册，这里固定用主线程 Looper，
                // 工具运行在后台线程时靠 latch 阻塞等待结果。
                runCatching { lm.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
                latch.await(8, TimeUnit.SECONDS)
                runCatching { lm.removeUpdates(listener) }
                loc = holder[0]
            }
            loc?.let {
                val mode = if (fine) "精确" else "粗略"
                "当前位置（$mode 定位）：纬度=${it.latitude}, 经度=${it.longitude}, 精度=${it.accuracy}m"
            } ?: "暂时拿不到位置，请确认系统的 GPS / 网络定位开关已打开后重试。"
        } catch (e: Exception) {
            "获取位置失败：${e.message}"
        }
    }

    private fun geocode(ctx: Context, args: JSONObject): String {
        if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return "未授予定位权限，请在「权限」页授予位置权限后重试。"
        }
        if (!Geocoder.isPresent()) return "该设备不支持地理编码服务"
        val gc = Geocoder(ctx, Locale.getDefault())
        val lat = args.optString("lat", "").toDoubleOrNull()
        val lng = args.optString("lng", "").toDoubleOrNull()
        return try {
            if (lat != null && lng != null) {
                @Suppress("DEPRECATION")
                val list = gc.getFromLocation(lat, lng, 3)
                if (list.isNullOrEmpty()) "（无结果）"
                else list.joinToString("\n") { it.getAddressLine(0) ?: "" }
            } else {
                val q = args.optString("query", "")
                if (q.isBlank()) return "缺少 query（地址）或 lat+lng（坐标）"
                @Suppress("DEPRECATION")
                val list = gc.getFromLocationName(q, 3)
                if (list.isNullOrEmpty()) "（无结果）"
                else list.joinToString("\n") { "${it.latitude},${it.longitude} | ${it.getAddressLine(0) ?: ""}" }
            }
        } catch (e: Exception) {
            "地理编码失败：${e.message}"
        }
    }

    // ---------- 短信 / 通话记录 ----------

    private fun readSms(ctx: Context, args: JSONObject): String {
        if (!granted(ctx, Manifest.permission.READ_SMS)) {
            return "未授予短信读取权限，请在「权限」页授予短信权限后重试。"
        }
        val limit = limitOf(args)
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return try {
            val proj = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
            // 不把 LIMIT 拼进 sortOrder：部分 ROM 的 provider 会抛 Invalid token LIMIT，
            // 条数改由下面的 while 截断控制。
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, proj, null, null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext() && out.size < limit) {
                    val addr = c.getString(0) ?: ""
                    val body = c.getString(1) ?: ""
                    val date = c.getLong(2)
                    out.add("[${fmt.format(Date(date))}] $addr：$body")
                }
                if (out.isEmpty()) "（收件箱没有短信）" else "最近 ${out.size} 条短信：\n" + out.joinToString("\n")
            } ?: "（读不到短信数据）"
        } catch (e: Exception) {
            "读取短信失败：${e.message}"
        }
    }

    private fun readCallLog(ctx: Context, args: JSONObject): String {
        if (!granted(ctx, Manifest.permission.READ_CALL_LOG)) {
            return "未授予通话记录权限，请在「权限」页授予「电话与通话记录」后重试。"
        }
        val limit = limitOf(args)
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return try {
            val proj = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            )
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, proj, null, null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext() && out.size < limit) {
                    val num = c.getString(0) ?: ""
                    val who = c.getString(1)?.takeIf { it.isNotBlank() } ?: num
                    val type = when (c.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "呼入"
                        CallLog.Calls.OUTGOING_TYPE -> "呼出"
                        CallLog.Calls.MISSED_TYPE -> "未接"
                        CallLog.Calls.REJECTED_TYPE -> "已拒接"
                        else -> "其它"
                    }
                    val date = c.getLong(3)
                    val dur = c.getLong(4)
                    out.add("[${fmt.format(Date(date))}] $type $who（${dur}秒）")
                }
                if (out.isEmpty()) "（没有通话记录）" else "最近 ${out.size} 条通话记录：\n" + out.joinToString("\n")
            } ?: "（读不到通话记录）"
        } catch (e: Exception) {
            "读取通话记录失败：${e.message}"
        }
    }

    // ---------- 媒体库 ----------

    private fun listMedia(ctx: Context, args: JSONObject): String {
        val kind = args.optString("kind", "image").lowercase()
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            when (kind) {
                "video" -> Manifest.permission.READ_MEDIA_VIDEO
                "audio" -> Manifest.permission.READ_MEDIA_AUDIO
                else -> Manifest.permission.READ_MEDIA_IMAGES
            }
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (!granted(ctx, perm)) {
            return "未授予媒体读取权限（$perm），请在「权限」页授予存储 / 媒体权限后重试。"
        }
        val limit = limitOf(args)
        val coll = when (kind) {
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val proj = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        return try {
            ctx.contentResolver.query(
                coll, proj, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(0) ?: ""
                    val size = c.getLong(1)
                    val date = c.getLong(2) * 1000L
                    val mb = String.format(Locale.US, "%.1f", size / 1024.0 / 1024.0)
                    out.add("$name（${mb}MB，${fmt.format(Date(date))}）")
                }
                if (out.isEmpty()) "（媒体库里没有$kind）" else "共列出 ${out.size} 项：\n" + out.joinToString("\n")
            } ?: "（媒体库为空）"
        } catch (e: Exception) {
            "读取媒体库失败：${e.message}"
        }
    }

    // ---------- 相机 / 蓝牙 / 电话 ----------

    private fun toggleFlashlight(ctx: Context, args: JSONObject): String {
        if (!granted(ctx, Manifest.permission.CAMERA)) {
            return "未授予相机权限，请在「权限」页授予相机权限后重试（手电筒由相机服务管理）。"
        }
        val raw = args.optString("on", "true")
        val on = raw.equals("true", true) || raw == "1" || args.optBoolean("on", true)
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return "该设备不支持相机服务"
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "该设备没有可用的闪光灯"
            cm.setTorchMode(id, on)
            if (on) "手电筒已打开" else "手电筒已关闭"
        } catch (e: Exception) {
            "操作手电筒失败：${e.message}"
        }
    }

    private fun bluetoothStatus(ctx: Context): String {
        val need = BLUETOOTH_PERMS.first()
        if (!granted(ctx, need)) {
            return "未授予蓝牙权限，请在「权限」页授予蓝牙权限后重试。"
        }
        return try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return "该设备不支持蓝牙"
            val paired = runCatching {
                adapter.bondedDevices.map { "${it.name ?: "未命名"}(${it.address})" }
            }.getOrDefault(emptyList())
            "蓝牙已开启=${adapter.isEnabled}；已配对设备：${if (paired.isEmpty()) "无" else paired.joinToString("; ")}"
        } catch (e: Exception) {
            "读取蓝牙状态失败：${e.message}"
        }
    }

    private fun phoneInfo(ctx: Context): String {
        if (!granted(ctx, Manifest.permission.READ_PHONE_STATE) &&
            !granted(ctx, Manifest.permission.READ_PHONE_NUMBERS)
        ) {
            return "未授予电话权限，请在「权限」页授予「电话与通话记录」后重试。"
        }
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return "该设备不支持电话服务"
            val sb = StringBuilder()
            sb.append("运营商：${tm.networkOperatorName.ifBlank { "未知" }}\n")
            sb.append("SIM 国家：${tm.simCountryIso.ifBlank { "未知" }}\n")
            val type = when (tm.phoneType) {
                TelephonyManager.PHONE_TYPE_GSM -> "GSM"
                TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                TelephonyManager.PHONE_TYPE_SIP -> "SIP"
                else -> "无 / 未知"
            }
            sb.append("网络制式：$type\n")
            val num = runCatching {
                @Suppress("DEPRECATION", "MissingPermission")
                tm.line1Number
            }.getOrNull()
            sb.append("本机号码：${num?.takeIf { it.isNotBlank() } ?: "运营商未写入（读不到属正常现象）"}")
            sb.toString()
        } catch (e: Exception) {
            "读取电话信息失败：${e.message}"
        }
    }

    // ---------- 日历写入 ----------

    private fun writeCalendar(ctx: Context, args: JSONObject): String {
        if (!granted(ctx, Manifest.permission.WRITE_CALENDAR)) {
            return "未授予日历写入权限，请在「权限」页授予日历权限后重试。"
        }
        val title = args.optString("title", "").trim()
        if (title.isEmpty()) return "缺少事件标题"
        val startMs = parseTime(args.optString("start", ""))
            ?: return "无法解析开始时间（支持 \"yyyy-MM-dd HH:mm\" 或毫秒时间戳）"
        val endMs = args.optString("end", "").let {
            if (it.isBlank()) startMs + 3600_000L else parseTime(it)
        } ?: return "无法解析结束时间"
        if (endMs < startMs) return "结束时间不能早于开始时间"
        val calId = resolveWritableCalendar(ctx)
            ?: return "设备上没有可写入的日历账户，请先在系统日历里创建一个本地日历再试。"
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            args.optString("location", "").trim().takeIf { it.isNotEmpty() }
                ?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            args.optString("description", "").trim().takeIf { it.isNotEmpty() }
                ?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        return try {
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return "写入日历失败：系统返回空（可能被日历应用拒绝或账户不可写）"
            "已写入日历：「$title」${fmtTime(startMs)} → ${fmtTime(endMs)}（事件ID=${uri.lastPathSegment ?: "?"}）"
        } catch (e: Exception) {
            "写入日历失败：${e.message}"
        }
    }

    private fun resolveWritableCalendar(ctx: Context): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE)
        val sel = "${CalendarContract.Calendars.VISIBLE} = 1"
        return runCatching {
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)
                ?.use { c ->
                    var pick: Long? = null
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val type = c.getString(1) ?: ""
                        if (type.contains("local", true)) return@use id
                        if (pick == null) pick = id
                    }
                    pick
                }
        }.getOrNull()
    }

    private fun parseTime(s: String): Long? {
        if (s.isBlank()) return null
        s.toLongOrNull()?.let { return it }
        for (f in arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
            runCatching { SimpleDateFormat(f, Locale.CHINA).parse(s)?.time }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))

    // ---------- 权限自检 ----------

    private fun checkPermissions(ctx: Context): String {
        val rows = mutableListOf<Triple<String, Boolean, String>>()
        fun add(label: String, vararg perms: String) {
            val ok = perms.any { granted(ctx, it) }
            rows.add(Triple(label, ok, perms.joinToString()))
        }
        add("定位", Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        add("拨打电话", Manifest.permission.CALL_PHONE)
        add("发送短信", Manifest.permission.SEND_SMS)
        add("读取短信", Manifest.permission.READ_SMS)
        add("联系人", Manifest.permission.READ_CONTACTS)
        add("日历读取", Manifest.permission.READ_CALENDAR)
        add("日历写入", Manifest.permission.WRITE_CALENDAR)
        add("相机 / 手电筒", Manifest.permission.CAMERA)
        add("蓝牙", *BLUETOOTH_PERMS.toTypedArray())
        add("媒体库", *MEDIA_PERMS.toTypedArray())
        add("通话记录", Manifest.permission.READ_CALL_LOG)
        add("电话信息", *PHONE_PERMS.toTypedArray())
        add("麦克风", Manifest.permission.RECORD_AUDIO)

        val okList = rows.filter { it.second }.joinToString("、") { it.first }
        val noList = rows.filterNot { it.second }.joinToString("、") { it.first }
        val sb = StringBuilder("权限自检（Android API ${Build.VERSION.SDK_INT}）：\n")
        sb.append("已授权：${okList.ifEmpty { "无" }}\n")
        sb.append("未授权：${noList.ifEmpty { "无" }}\n")
        if (noList.isNotEmpty()) {
            sb.append("提示：未授权项可在 App 的「权限」页一键授予；授予后同一句话再说一次即可生效。")
        } else {
            sb.append("全部权限已到位，相关工具都可以直接调用。")
        }
        return sb.toString()
    }
}
