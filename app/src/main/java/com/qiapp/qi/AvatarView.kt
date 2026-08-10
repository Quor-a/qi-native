package com.qiapp.qi

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.animation.ValueAnimator
import kotlin.math.*

/**
 * 动态形象角色视图（AI 设计立绘 + 自然口形叠加）。
 *
 * 核心设计：
 *  - 角色：加载 AI 生成的正面立绘 PNG（按 [style] 选择主题色变体），作为「设计师设计」的人物形象。
 *  - 口形：在立绘嘴巴位置叠加真人嘴型（Cupid 弓上唇 + 饱满下唇 + 张口时深色口腔），
 *         由 [setMouth] 驱动开合。空闲时不绘制（露出立绘自带的闭合嘴线）；说话时覆盖并动画。
 *  - 表情：[setEmotion] 改变嘴型弧度（笑/撇/圆张）。
 *  - 活人感：整幅立绘随「呼吸」浮动（错频有机运动），背后柔光脉动。
 *  - 兜底：若立绘加载失败，回退到纯 Canvas 卡通模式（保留原有 drawBody/drawCartoonFace）。
 *
 * 颜色随 [style]（0 温柔 / 1 活泼 / 2 高冷）与 [accentColor]（灵魂主题色）变化。
 */
class AvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 灵魂主题色（用于身体渐变、头发、柔光脉动）。 */
    var accentColor: Int = 0xFFE86A8C.toInt()
    /** 0 温柔 / 1 活泼 / 2 高冷（影响肤色、眉毛弧度、以及选择哪张立绘）。 */
    var style: Int = 0
        set(v) {
            if (field != v) {
                field = v
                loadCharacterBitmap()
                invalidate()
            }
        }

    private var mouthOpen = 0f
    private var emotion = EmotionAnalyzer.Emotion.NEUTRAL
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = java.util.Random(System.currentTimeMillis())
    private var blinkAnim: ValueAnimator? = null
    private var idleAnim: ValueAnimator? = null
    private var idleT = 0f
    private var attached = false

    // ── AI 设计立绘 ──
    private var charBitmap: Bitmap? = null

    companion object {
        /** 立绘中脸中心的归一化坐标（从生成图像目测）。 */
        const val FACE_NX = 0.50f
        const val FACE_NY = 0.38f
        /** 立绘中嘴巴的归一化坐标。 */
        const val MOUTH_NX = 0.50f
        const val MOUTH_NY = 0.53f
        /** 嘴巴半宽（归一化于立绘宽度）。 */
        const val MOUTH_HW_NORM = 0.055f
        /** 立绘中脸部宽度占图像总宽的比例（用于缩放计算）。 */
        const val FACE_WIDTH_RATIO = 0.47f

        /** 各 style 对应的立绘 drawable 资源 ID 数组。 */
        private var _charIds: IntArray? = null

        /** 延迟解析资源 ID（避免在 companion init 时访问 Context/Resources）。 */
        internal fun resolveCharResIds(res: android.content.res.Resources): IntArray {
            if (_charIds == null) {
                _charIds = intArrayOf(
                    res.getIdentifier("avatar_char_0", "drawable", "com.qiapp.qi")
                        .takeIf { it != 0 } ?: 0,
                    res.getIdentifier("avatar_char_1", "drawable", "com.qiapp.qi")
                        .takeIf { it != 0 } ?: 0,
                    res.getIdentifier("avatar_char_2", "drawable", "com.qiapp.qi")
                        .takeIf { it != 0 } ?: 0
                )
            }
            return _charIds!!
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        loadCharacterBitmap()
        startIdle()
        scheduleBlink()
    }

    override fun onDetachedFromWindow() {
        attached = false
        idleAnim?.cancel(); idleAnim = null
        handler.removeCallbacksAndMessages(null)
        blinkAnim?.cancel()
        charBitmap?.recycle(); charBitmap = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { if (attached) startIdle() } else idleAnim?.pause()
    }

    /** 持续「呼吸」浮动，让角色始终在动（不说话时也不静止）。 */
    private fun startIdle() {
        if (idleAnim?.isRunning == true) return
        idleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { idleT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** 设置嘴部开合度（0..1），由说话幅度驱动。 */
    fun setMouth(v: Float) {
        val c = v.coerceIn(0f, 1f)
        if (abs(c - mouthOpen) > 0.012f) {
            mouthOpen = c
            invalidate()
        }
    }

    /** 设置情绪（key 取自 [EmotionAnalyzer.Emotion.key]）。 */
    fun setEmotion(key: String) {
        val e = EmotionAnalyzer.Emotion.fromKey(key)
        if (e != emotion) {
            emotion = e
            invalidate()
        }
    }

    private fun scheduleBlink() {
        if (!attached) return
        val delay = (1800 + rnd.nextInt(2600)).toLong()
        handler.postDelayed({
            if (attached && visibility == VISIBLE) doBlink()
            scheduleBlink()
        }, delay)
    }

    private fun doBlink() {
        blinkAnim?.cancel()
        blinkAnim = ValueAnimator.ofFloat(1f, 0.12f, 1f).apply {
            duration = 170
            addUpdateListener {
                invalidate()  // 立绘模式下眨眼暂不实现（图像眼睛静态），仅触发重绘
            }
            start()
        }
    }

    // ---------- 颜色工具 ----------
    private fun darken(color: Int, f: Float): Int {
        val a = (color shr 24) and 0xFF
        val r = ((color shr 16) and 0xFF) * f
        val g = ((color shr 8) and 0xFF) * f
        val b = (color and 0xFF) * f
        return (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private fun skinColor(): Int = when (style) {
        2 -> 0xFFF0E4DC.toInt()
        1 -> 0xFFFAD2B0.toInt()
        else -> 0xFFF8D9C4.toInt()
    }

    private fun hairColor(): Int = when (style) {
        2 -> 0xFF3A3340.toInt()
        1 -> darken(accentColor, 0.5f)
        else -> darken(accentColor, 0.62f)
    }

    // ========== 主渲染 ==========
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val faceR = (w.coerceAtMost(h) * 0.30f).coerceAtLeast(40f)
        val faceCy = h * 0.40f

        // 多层错频的有机待机运动
        val ph = idleT * 2f * PI.toFloat()
        val bobY = sin(ph) * faceR * 0.045f
        val swayX = sin(ph * 0.5f + 0.6f) * faceR * 0.03f
        val tilt = sin(ph * 1.5f + 1.2f) * 0.045f
        val breathe = 1f + sin(ph * 0.5f) * 0.012f

        drawGlow(canvas, cx, faceCy, faceR)

        // 以胸腔为支点做旋转/缩放/平移（整幅立绘一起动）
        val pivotY = faceCy + faceR * 1.2f
        canvas.save()
        canvas.translate(cx, pivotY)
        canvas.rotate(tilt * 180f / PI.toFloat())
        canvas.scale(breathe, breathe)
        canvas.translate(-cx, -pivotY)
        canvas.translate(swayX, bobY)

        if (charBitmap != null) {
            // ── AI 立绘模式 ──
            drawCharacterImage(canvas, cx, faceCy, faceR)
            // 仅在说话或非中性表情时叠加口形（空闲中性时露出立绘自带嘴线）
            if (mouthOpen > 0.015f || emotion != EmotionAnalyzer.Emotion.NEUTRAL) {
                drawNaturalMouthOverlay(canvas, cx, faceCy, faceR)
            }
        } else {
            // ── 兜底 Canvas 卡通模式 ──
            drawBody(canvas, cx, faceCy, faceR, h)
            drawCartoonFace(canvas, cx, faceCy, faceR)
        }

        canvas.restore()
    }

    // ────────────────────────────────
    //  AI 立绘渲染
    // ────────────────────────────────

    /** 加载当前 [style] 对应的立绘 Bitmap。 */
    private fun loadCharacterBitmap() {
        try {
            val ids = resolveCharResIds(resources)
            val resId = ids.getOrElse(style) { ids[0] }
            if (resId == 0) { charBitmap = null; return }
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = BitmapFactory.decodeResource(resources, resId, opts)
            charBitmap = if (bmp != null && !bmp.isRecycled) bmp else null
        } catch (_: Exception) {
            charBitmap = null
        }
    }

    /**
     * 绘制 AI 立绘位图，按比例适配使脸中心对齐 [faceCy]。
     *
     * 缩放策略：让立绘中的脸部宽度（约占图像 47%）映射为 [faceR]*2，
     * 从而整幅图的尺寸自然匹配画布。
     */
    private fun drawCharacterImage(canvas: Canvas, cx: Float, faceCy: Float, faceR: Float) {
        val bmp = charBitmap ?: return
        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        val scale = (faceR * 2f) / (iw * FACE_WIDTH_RATIO)
        val dw = iw * scale
        val dh = ih * scale
        val left = cx - FACE_NX * dw
        val top = faceCy - FACE_NY * dh
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), paint)
    }

    /**
     * 在立绘嘴巴位置叠加自然真人嘴型。
     *
     * 坐标通过归一化值从立绘映射到画布，确保不同分辨率/风格下嘴巴始终对准。
     * 形状模拟真实人类嘴唇结构（上唇 Cupid 弓 + 下唇饱满弧 + 张口深色口腔），
     * 解决用户反馈的「o 形状不像人嘴」问题。
     */
    private fun drawNaturalMouthOverlay(canvas: Canvas, cx: Float, faceCy: Float, faceR: Float) {
        val bmp = charBitmap ?: return
        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        val scale = (faceR * 2f) / (iw * FACE_WIDTH_RATIO)
        val dw = iw * scale
        val dh = ih * scale
        val left = cx - FACE_NX * dw
        val top = faceCy - FACE_NY * dh

        // 嘴巴在画布中的绝对坐标
        val mx = left + MOUTH_NX * dw
        val my = top + MOUTH_NY * dh
        val hw = MOUTH_HW_NORM * dw          // 嘴巴半宽（画布单位）

        val open = mouthOpen.coerceIn(0f, 1f)

        // 唇色 & 口腔内色
        val lipCol = 0xC88B7A.toInt()
        val innerCol = 0x6B3329.toInt()

        when (emotion) {
            EmotionAnalyzer.Emotion.SURPRISED -> {
                val r = hw * (0.80f + open * 0.70f)
                // 口腔
                canvas.drawOval(mx - r, my - r * 0.70f, mx + r, my + r * 1.25f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = innerCol })
                // 上唇弧线
                canvas.drawArc(RectF(mx - r, my - r * 1.05f, mx + r, my + r * 0.25f),
                    180f, 180f, false,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = lipCol; strokeWidth = hw * 0.17f; style = Paint.Style.STROKE
                    })
            }
            EmotionAnalyzer.Emotion.HAPPY, EmotionAnalyzer.Emotion.CALM -> {
                val sd = hw * (0.32f + open * 0.52f)
                val p = Path().apply {
                    moveTo(mx - hw, my)
                    quadTo(cx, my + sd * 1.8f, mx + hw, my)
                    if (open > 0.07f) {
                        lineTo(mx + hw * 0.62f, my + sd * 0.82f)
                        quadTo(cx, my + sd * 1.30f, mx - hw * 0.62f, my + sd * 0.82f)
                    }
                    close()
                }
                canvas.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lipCol })
            }
            EmotionAnalyzer.Emotion.SAD, EmotionAnalyzer.Emotion.ANGRY -> {
                val d = hw * (0.26f + open * 0.38f)
                val p = Path().apply {
                    moveTo(mx - hw, my + d)
                    quadTo(cx, my - d * 0.34f, mx + hw, my + d)
                    close()
                }
                canvas.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lipCol })
            }
            else -> {
                // 中性自然嘴型（主要说话状态）
                drawRealisticMouth(canvas, mx, my, hw, open, lipCol, innerCol)
            }
        }
    }

    /**
     * 真人中性嘴型 —— 核心修复项。
     *
     * 结构：
     *  - 闭合时：极细唇线（或完全透明以露出立绘嘴线）。
     *  - 微张时：上唇 Cupid 弓（中间微凹、两端上挑）+ 下唇饱满弧线。
     *  - 明显张开时：中间填充深色口腔椭圆 + 上方牙齿暗示。
     *
     * 这替代了原来的简单圆形(o)/弧线，解决「o 形状不像人类嘴巴」。
     */
    private fun drawRealisticMouth(
        canvas: Canvas, cx: Float, cy: Float, hw: Float,
        open: Float, lipCol: Int, innerCol: Int
    ) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = lipCol
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = lipCol
            strokeWidth = maxOf(hw * 0.14f, 1.2f); strokeCap = Paint.Cap.ROUND
        }
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = innerCol
        }

        if (open < 0.035f) {
            // 几乎闭合：只画一条极细唇线（微微上扬的淡微笑），不遮挡立绘原嘴
            canvas.drawLine(cx - hw * 0.82f, cy + hw * 0.06f,
                           cx + hw * 0.82f, cy + hw * 0.09f, strokePaint)
            return
        }

        // ── 上唇：Cupid 弓 ──
        val upperLip = Path().apply {
            moveTo(cx - hw, cy + hw * 0.01f)
            quadTo(cx - hw * 0.54f, cy - hw * 0.20f, cx - hw * 0.14f, cy - hw * 0.06f)
            quadTo(cx, cy + hw * 0.01f, cx + hw * 0.14f, cy - hw * 0.06f)
            quadTo(cx + hw * 0.54f, cy - hw * 0.20f, cx + hw, cy + hw * 0.01f)
            close()
        }
        canvas.drawPath(upperLip, fillPaint)

        // ── 下唇：饱满弧线 ──
        val lowerExtent = hw * (0.20f + open * 0.68f)
        val lowerLip = Path().apply {
            moveTo(cx - hw, cy + hw * 0.01f)
            quadTo(cx, cy + lowerExtent + hw * 0.12f, cx + hw, cy + hw * 0.01f)
            close()
        }
        canvas.drawPath(lowerLip, fillPaint)

        // ── 张口：口腔内部（深色椭圆） ──
        if (open > 0.07f) {
            val mouthH = hw * (0.30f + open * 0.85f)
            canvas.drawOval(
                cx - hw * 0.70f, cy + hw * 0.01f,
                cx + hw * 0.70f, cy + mouthH,
                innerPaint
            )

            // 牙齿暗示（明显张口时上方浅色条）
            if (open > 0.22f) {
                val teethPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFF5EEE0.toInt(); style = Paint.Style.FILL
                }
                canvas.drawRoundRect(
                    cx - hw * 0.52f, cy + hw * 0.02f,
                    cx + hw * 0.52f, cy + hw * (0.10f + open * 0.14f),
                    hw * 0.08f, hw * 0.06f, teethPaint
                )
            }
        }
    }

    // ────────────────────────────────
    //  兜底 Canvas 卡通模式（立绘不可用时）
    // ────────────────────────────────

    /** 背后柔光脉动。 */
    private fun drawGlow(canvas: Canvas, cx: Float, faceCy: Float, faceR: Float) {
        val pulse = 0.16f + 0.12f * (0.5f + 0.5f * sin(idleT * Math.PI.toFloat() * 2f))
        val glowR = faceR * 2.7f
        val a = (pulse * 255).toInt().coerceIn(0, 255)
        val col = Color.argb(a, (accentColor shr 16) and 0xFF, (accentColor shr 8) and 0xFF, accentColor and 0xFF)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, faceCy, glowR * 0.2f, col, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, faceCy + faceR * 0.4f, glowR, paint)
    }

    /** 身体：脖子 + 肩膀 + 躯干 + 手臂。 */
    private fun drawBody(canvas: Canvas, cx: Float, faceCy: Float, faceR: Float, h: Float) {
        val neckTopY = faceCy + faceR * 0.80f
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(cx, neckTopY, cx, h,
                accentColor, darken(accentColor, 0.66f), Shader.TileMode.CLAMP)
        }
        val nw = faceR * 0.34f
        canvas.drawRect(cx - nw, neckTopY, cx + nw, faceCy + faceR * 1.32f, bodyPaint)
        val topY = faceCy + faceR * 0.78f
        val shoulderY = faceCy + faceR * 1.5f
        val shoulderW = faceR * 2.0f
        val bottomW = faceR * 2.3f
        val torso = Path().apply {
            moveTo(cx - bottomW, h + 4f)
            lineTo(cx - shoulderW, shoulderY)
            quadTo(cx - faceR * 0.5f, topY + faceR * 0.18f, cx - faceR * 0.30f, topY)
            lineTo(cx + faceR * 0.30f, topY)
            quadTo(cx + faceR * 0.5f, topY + faceR * 0.18f, cx + shoulderW, shoulderY)
            lineTo(cx + bottomW, h + 4f)
            close()
        }
        canvas.drawPath(torso, bodyPaint)
        val armTopY = faceCy + faceR * 1.38f
        val armBotY = (faceCy + faceR * 3.5f).coerceAtMost(h - faceR * 0.12f)
        val armOuter = faceR * 2.05f
        val armInner = faceR * 0.55f
        val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darken(accentColor, 0.5f); alpha = 55
            strokeWidth = faceR * 0.05f; style = Paint.Style.STROKE
        }
        for (sx in listOf(-1f, 1f)) {
            val l = minOf(cx + sx * armOuter, cx + sx * armInner)
            val rgt = maxOf(cx + sx * armOuter, cx + sx * armInner)
            val rad = (rgt - l) / 2f
            canvas.drawRoundRect(l, armTopY, rgt, armBotY, rad, rad, bodyPaint)
            val sepX = cx + sx * (armInner + faceR * 0.06f)
            canvas.drawLine(sepX, armTopY + faceR * 0.25f, sepX, armBotY - rad, sepPaint)
        }
        val collarY = faceCy + faceR * 1.02f
        val cw = faceR * 0.5f
        val collar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(accentColor, 0.52f) }
        val cp = Path().apply {
            moveTo(cx - cw, collarY)
            lineTo(cx, collarY + faceR * 0.42f)
            lineTo(cx + cw, collarY)
            lineTo(cx + cw, collarY + faceR * 0.20f)
            lineTo(cx, collarY + faceR * 0.62f)
            lineTo(cx - cw, collarY + faceR * 0.20f)
            close()
        }
        canvas.drawPath(cp, collar)
    }

    /** 卡通脸（兜底）。 */
    private fun drawCartoonFace(canvas: Canvas, cx: Float, faceCy: Float, faceR: Float) {
        val skin = skinColor()
        canvas.drawCircle(cx, faceCy, faceR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin })
        canvas.drawCircle(cx, faceCy, faceR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = faceR * 0.03f; color = darken(skin, 0.85f)
        })
        val hairCol = hairColor()
        val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hairCol }
        val hp = Path().apply {
            moveTo(cx - faceR * 0.95f, faceCy + faceR * 0.55f)
            lineTo(cx - faceR * 1.02f, faceCy + faceR * 0.15f)
            arcTo(RectF(cx - faceR * 1.05f, faceCy - faceR * 1.05f, cx + faceR * 1.05f, faceCy + faceR * 1.05f), 198f, 144f, false)
            lineTo(cx + faceR * 0.95f, faceCy + faceR * 0.55f)
            quadTo(cx + faceR * 0.45f, faceCy - faceR * 0.30f, cx + faceR * 0.18f, faceCy + faceR * 0.02f)
            quadTo(cx, faceCy + faceR * 0.22f, cx - faceR * 0.18f, faceCy + faceR * 0.02f)
            quadTo(cx - faceR * 0.45f, faceCy - faceR * 0.30f, cx - faceR * 0.95f, faceCy + faceR * 0.55f)
            close()
        }
        canvas.drawPath(hp, hair)
        canvas.drawCircle(cx + faceR * 0.72f, faceCy - faceR * 0.42f, faceR * 0.13f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor })
        canvas.drawCircle(cx + faceR * 0.72f, faceCy - faceR * 0.42f, faceR * 0.06f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(accentColor, 0.7f) })

        val blush = when (emotion) {
            EmotionAnalyzer.Emotion.HAPPY -> 0.5f
            EmotionAnalyzer.Emotion.CALM -> 0.32f
            EmotionAnalyzer.Emotion.SURPRISED -> 0.3f
            else -> 0f
        }
        if (blush > 0f) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFF9AA2.toInt(); alpha = (blush * 150).toInt()
            }
            canvas.drawCircle(cx - faceR * 0.5f, faceCy + faceR * 0.18f, faceR * 0.16f, bp)
            canvas.drawCircle(cx + faceR * 0.5f, faceCy + faceR * 0.18f, faceR * 0.16f, bp)
        }
        drawEyes(canvas, cx, faceCy, faceR)
        drawBrows(canvas, cx, faceCy, faceR)
        drawMouth(canvas, cx, faceCy, faceR, 0x4A3B3B.toInt(), 1f)
    }

    private fun drawEyes(canvas: Canvas, cx: Float, faceCy: Float, faceR: Float) {
        val eyeY = faceCy - faceR * 0.02f
        val eyeRx = faceR * 0.13f
        val baseRy = faceR * 0.17f
        val ry = when (emotion) {
            EmotionAnalyzer.Emotion.SURPRISED -> baseRy * 1.35f
            EmotionAnalyzer.Emotion.HAPPY -> baseRy * 0.8f
            else -> baseRy
        } * 1f  // 立绘模式下不缩放眼睛（图像已有眼睛）
        val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332B2B.toInt() }
        for (sx in listOf(-1f, 1f)) {
            val ex = cx + sx * faceR * 0.38f
            canvas.drawOval(ex - eyeRx, eyeY - ry, ex + eyeRx, eyeY + ry, eyePaint)
            if (true) {  // 兜底卡通才需要高光
                canvas.drawCircle(ex + eyeRx * 0.3f, eyeY - ry * 0.3f, eyeRx * 0.32f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            }
        }
    }

    private fun drawBrows(canvas: Canvas, cx: Float, faceCy: Float, faceR: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hairColor(); strokeWidth = faceR * 0.07f
            strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
        }
        val y = faceCy - faceR * 0.32f
        val dx = faceR * 0.40f
        val len = faceR * 0.34f
        val (innerDy, outerDy) = when (emotion) {
            EmotionAnalyzer.Emotion.ANGRY -> Pair(-faceR * 0.10f, faceR * 0.06f)
            EmotionAnalyzer.Emotion.SAD -> Pair(-faceR * 0.08f, faceR * 0.02f)
            EmotionAnalyzer.Emotion.SURPRISED -> Pair(-faceR * 0.04f, -faceR * 0.04f)
            EmotionAnalyzer.Emotion.HAPPY -> Pair(-faceR * 0.02f, -faceR * 0.05f)
            else -> Pair(0f, 0f)
        }
        for (sx in listOf(-1f, 1f)) {
            val innerX = cx + sx * (dx - len / 2)
            val outerX = cx + sx * (dx + len / 2)
            canvas.drawLine(innerX, y + innerDy, outerX, y + outerDy, paint)
        }
    }

    /** 兜底卡通嘴（仅 Canvas 模式使用）。 */
    private fun drawMouth(canvas: Canvas, cx: Float, faceCy: Float, faceR: Float, color: Int, alpha: Float) {
        val my = faceCy + faceR * 0.46f
        val mw = faceR * 0.42f
        val open = mouthOpen
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; this.alpha = (alpha * 255).toInt().coerceIn(0, 255); style = Paint.Style.FILL
        }
        when (emotion) {
            EmotionAnalyzer.Emotion.SURPRISED -> {
                canvas.drawCircle(cx, my, faceR * (0.10f + open * 0.18f), paint)
            }
            EmotionAnalyzer.Emotion.HAPPY, EmotionAnalyzer.Emotion.CALM -> {
                val top = my - faceR * 0.06f
                val depth = faceR * (0.10f + open * 0.22f)
                val p = Path().apply {
                    moveTo(cx - mw, top)
                    quadTo(cx, top + depth * 2, cx + mw, top)
                    if (open > 0.05f) {
                        lineTo(cx + mw * 0.6f, top + depth)
                        quadTo(cx, top + depth * 1.4f, cx - mw * 0.6f, top + depth)
                    }
                    close()
                }
                canvas.drawPath(p, paint)
            }
            EmotionAnalyzer.Emotion.SAD, EmotionAnalyzer.Emotion.ANGRY -> {
                val top = my + faceR * 0.04f
                val depth = faceR * (0.10f + open * 0.20f)
                val p = Path().apply {
                    moveTo(cx - mw, top + depth)
                    quadTo(cx, top - depth * 0.4f, cx + mw, top + depth)
                    close()
                }
                canvas.drawPath(p, paint)
            }
            else -> {
                val top = my - faceR * 0.02f
                val depth = faceR * (0.07f + open * 0.22f)
                val p = Path().apply {
                    moveTo(cx - mw, top)
                    quadTo(cx, top + depth * 1.6f, cx + mw, top)
                    if (open > 0.05f) {
                        lineTo(cx + mw * 0.6f, top + depth)
                        quadTo(cx, top + depth * 1.25f, cx - mw * 0.6f, top + depth)
                    }
                    close()
                }
                canvas.drawPath(p, paint)
            }
        }
    }
}
