package com.qiapp.qi

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView

/** 把 ImageView 裁成圆形（头像统一圆形，对齐 ZorvAI 风格）。 */
fun ImageView.toCircle() {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }
    clipToOutline = true
}
