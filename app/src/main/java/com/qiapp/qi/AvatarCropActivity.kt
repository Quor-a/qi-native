package com.qiapp.qi

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 交互式头像裁剪页：图片可在框内双指缩放、单指拖动，
 * 中间圆形取景框即最终头像范围，确认后输出 512×512 PNG 到 filesDir/avatar_<idx>.png。
 * 纯代码实现，不依赖任何第三方裁剪库，离线构建安全。
 */
class AvatarCropActivity : AppCompatActivity() {

    private lateinit var frame: FrameLayout
    private lateinit var img: ImageView
    private lateinit var overlay: CropOverlayView
    private var srcBitmap: Bitmap? = null
    private val matrix = Matrix()
    private var baseScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var idx = 0
    private lateinit var scaleDetector: ScaleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_avatar_crop)

        frame = findViewById(R.id.cropFrame)
        img = findViewById(R.id.cropImg)
        overlay = findViewById(R.id.cropOverlay)
        idx = intent.getIntExtra("idx", 0)
        val srcPath = intent.getStringExtra("src")
        if (srcPath.isNullOrEmpty()) { finish(); return }

        srcBitmap = decodeSampled(srcPath, 2048)
        if (srcBitmap == null) {
            Toast.makeText(this, "图片无法打开", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        img.setImageBitmap(srcBitmap)
        img.scaleType = ImageView.ScaleType.MATRIX

        // 等布局完成后再设定初始变换与取景圆参数
        frame.post {
            val vw = img.width.toFloat()
            val vh = img.height.toFloat()
            val bw = srcBitmap!!.width.toFloat()
            val bh = srcBitmap!!.height.toFloat()
            val scale = maxOf(vw / bw, vh / bh)
            baseScale = scale
            matrix.setScale(scale, scale)
            matrix.postTranslate((vw - bw * scale) / 2f, (vh - bh * scale) / 2f)
            img.imageMatrix = matrix

            val r = minOf(vw, vh) * 0.375f
            overlay.cx = vw / 2f
            overlay.cy = vh / 2f
            overlay.radius = r
            overlay.invalidate()
        }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val cur = matrixScale()
                val newScale = (cur * factor).coerceIn(baseScale * 0.5f, baseScale * 8f)
                val real = newScale / cur
                matrix.postScale(real, real, detector.focusX, detector.focusY)
                clamp()
                img.imageMatrix = matrix
                return true
            }
        })

        frame.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; dragging = true }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && dragging) {
                        matrix.postTranslate(e.x - lastX, e.y - lastY)
                        lastX = e.x; lastY = e.y
                        clamp()
                        img.imageMatrix = matrix
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true
        }

        findViewById<android.widget.Button>(R.id.cropCancel)?.setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        findViewById<android.widget.Button>(R.id.cropDone)?.setOnClickListener { doCrop() }
    }

    private fun matrixScale(): Float {
        val v = FloatArray(9); matrix.getValues(v); return v[Matrix.MSCALE_X]
    }
    private fun matrixTx(): Float {
        val v = FloatArray(9); matrix.getValues(v); return v[Matrix.MTRANS_X]
    }
    private fun matrixTy(): Float {
        val v = FloatArray(9); matrix.getValues(v); return v[Matrix.MTRANS_Y]
    }

    /** 限制平移，保证图片始终至少覆盖整个 ImageView（避免露出黑边） */
    private fun clamp() {
        val vw = img.width.toFloat(); val vh = img.height.toFloat()
        val bw = (srcBitmap?.width ?: 1).toFloat(); val bh = (srcBitmap?.height ?: 1).toFloat()
        val scale = matrixScale()
        val dispW = bw * scale; val dispH = bh * scale
        var tx = matrixTx(); var ty = matrixTy()
        tx = if (dispW <= vw) (vw - dispW) / 2f else tx.coerceIn(vw - dispW, 0f)
        ty = if (dispH <= vh) (vh - dispH) / 2f else ty.coerceIn(vh - dispH, 0f)
        matrix.setScale(scale, scale)
        matrix.postTranslate(tx, ty)
    }

    private fun doCrop() {
        val vw = img.width; val vh = img.height
        val r = overlay.radius
        val left = (vw / 2f - r); val top = (vh / 2f - r)
        val right = left + 2 * r; val bottom = top + 2 * r

        val inv = Matrix(); matrix.invert(inv)
        val pts = floatArrayOf(left, top, right, bottom)
        inv.mapPoints(pts)
        val sx = pts[0].toInt().coerceAtLeast(0)
        val sy = pts[1].toInt().coerceAtLeast(0)
        val ex = pts[2].toInt().coerceAtMost(srcBitmap!!.width)
        val ey = pts[3].toInt().coerceAtMost(srcBitmap!!.height)
        val w = (ex - sx).coerceAtLeast(1)
        val h = (ey - sy).coerceAtLeast(1)

        val raw = runCatching { Bitmap.createBitmap(srcBitmap!!, sx, sy, w, h) }.getOrNull()
        if (raw == null) { Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show(); return }

        // 等比缩放居中到 512x512，四周补透明
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = minOf(512f / w, 512f / h)
        val dw = w * s; val dh = h * s
        c.drawBitmap(raw, null, RectF((512 - dw) / 2f, (512 - dh) / 2f, (512 + dw) / 2f, (512 + dh) / 2f), null)

        val file = File(filesDir, "avatar_$idx.png")
        runCatching { file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        raw.recycle(); out.recycle()

        setResult(Activity.RESULT_OK, Intent().putExtra("path", file.absolutePath))
        finish()
    }

    private fun decodeSampled(path: String, maxSide: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val bw = opts.outWidth; val bh = opts.outHeight
        var sample = 1
        while (bw / sample > maxSide || bh / sample > maxSide) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
