package com.qiapp.qi

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行时权限请求网关（移植自上游 ZorvAI QuroPermissionGate）。
 *
 * 背景：AI 工具运行在 `qi-llm` 后台线程上，无法自行弹出系统授权框。
 * 由承载对话的 [MainActivity] 注入 [PermissionRequester] 实现，引擎在派发需要危险
 * 权限的工具前，先通过网关确保权限到位——要么直接放行，要么在 UI 线程弹框、
 * 后台线程阻塞等待用户授权结果。
 *
 * 这补齐了 fork 此前缺失的一环：旧实现只在工具内用 checkSelfPermission 检查，
 * 缺权限时仅返回"未授权"文本、从不主动请求，导致用户在系统设置授予后也常出现
 * "给了权限但 AI 用不了"的体感（实际是 fork 从未去消费这些权限）。
 *
 * 对齐上游 #766 修复：对话框成功授权后，ensure 的 continuation 可能因子 Activity 失焦/
 * 重建而返回 false；此处以系统真实状态二次核验，已授权即放行，不再误拒。
 */
interface PermissionRequester {
    /**
     * 确保 [permissions] 全部已授予。缺失则在 UI 线程弹系统授权框，
     * 后台线程阻塞等待结果，返回是否全部已授权。
     */
    fun ensure(permissions: List<String>): Boolean
}

object PermissionGate {

    /** 由承载对话的 Activity 在 onStart 注入、onStop 清空。 */
    var requester: PermissionRequester? = null

    /** 在任意 Context 上检查权限是否全部已授予（无需 Activity，同步读系统状态）。 */
    fun isGranted(context: Context, permissions: List<String>): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * 只要 [permissions] 中**任一**已授予即为真。
     *
     * 对齐上游教训（#766 / #768）：同一工具并列声明多个权限时，「全部满足」是错误语义——
     *  - 定位的 FINE / COARSE：只授予其中一个也完全能定位；
     *  - 媒体的 IMAGES / VIDEO / AUDIO：只授予图片也能列图片；
     *  - 跨版本权限（API 33+ 的 READ_EXTERNAL_STORAGE、API 31+ 的 legacy BLUETOOTH）
     *    在当前系统上恒为 DENIED，一旦并列进来会把「全部满足」永久钉死为 false。
     *
     * 结果就是用户在系统里明明授权了，工具仍然被门禁拒掉——即「权限给了却用不了」。
     */
    fun anyGranted(context: Context, permissions: List<String>): Boolean =
        permissions.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * 后台线程安全入口：已授权直接返回 true；否则经 [requester] 弹框并阻塞等待。
     * 若没有任何 Activity 可拉起对话框（如纯后台场景），返回 false 交由上层提示用户去设置页。
     * 含 #766 二次核验：确保弹框授权成功后即便 continuation 误报 false，也以系统真实状态放行。
     */
    fun ensureGranted(context: Context, permissions: List<String>): Boolean {
        if (permissions.isEmpty()) return true
        if (isGranted(context, permissions)) return true
        val r = requester ?: return false
        val ok = runCatching { r.ensure(permissions) }.getOrDefault(false)
        // #766：弹框授权成功但 ensure 因 Activity 失焦返回 false 时，以系统真实状态放行。
        return if (ok) true else isGranted(context, permissions)
    }

    /**
     * 「任一满足」版网关：工具声明的一组等价权限里只要有一个已授予就放行。
     *
     * 这是工具引擎默认使用的入口——多权限工具用「全部满足」会造成大面积误拒
     * （详见 [anyGranted] 注释）。全都没授予时才弹框请求整组，让系统去决定
     * 哪些能授、哪些当前版本不可授；弹框返回后再以系统真实状态二次核验。
     */
    fun ensureAnyGranted(context: Context, permissions: List<String>): Boolean {
        if (permissions.isEmpty()) return true
        if (anyGranted(context, permissions)) return true
        val r = requester ?: return false
        runCatching { r.ensure(permissions) }
        // 不采信 requester 的布尔返回：它是「全部授予」语义，且可能因 Activity
        // 失焦/重建误报 false。一律以系统真实状态为准，任一到手即放行。
        return anyGranted(context, permissions)
    }
}
