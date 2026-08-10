package com.qiapp.qi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View

/**
 * 头像裁剪取景遮罩：四周半透明暗化，中间圆形区域擦除为透明（露出下方图片），
 * 并描一圈白色边框，直观指示最终头像范围。
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dimPaint = Paint().apply { color = Color.parseColor("#B3000000") }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }
    private val strokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    /** 取景圆中心与半径（单位 px），由 AvatarCropActivity 在布局完成后设置 */
    var cx = 0f
    var cy = 0f
    var radius = 0f

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        // 1) 整层暗化，再擦出中间圆（露出下方 ImageView 内容）
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawCircle(cx, cy, radius, clearPaint)
        canvas.restoreToCount(layer)
        // 2) 圆形描边
        canvas.drawCircle(cx, cy, radius, strokePaint)
    }
}
