package com.qiapp.qi

import android.content.Context
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * 表情包系统：内置几十个 emoji 贴纸 + 图片贴纸（assets/stickers/ 与用户添加）。
 *
 * - emoji 贴纸：几十个，满足「内置几十个」，AI 可直接挑。
 * - 图片贴纸：assets/stickers/ 下的 png（放进图即生效，自动发现）+ 用户/AI 从相册添加的图（file:路径）。
 * - AI 发朋友圈时从 [pickerHint] 里挑一个；用户也能在朋友圈页手动添加图片贴纸。
 *
 * 贴纸字段统一约定：
 *   - emoji 字符串（如 "😂"）
 *   - "asset:文件名.png"：内置图片贴纸
 *   - "file:/绝对路径"：用户/AI 添加的图片贴纸
 */
object StickerPack {

    /** 内置 emoji 贴纸（几十个，满足「内置几十个」）。 */
    val emojiStickers: List<String> = listOf(
        "😀", "😂", "🤣", "😊", "😍", "😘", "🥰", "😎", "🤔", "😏",
        "😴", "🥱", "😭", "😤", "😡", "🥺", "😩", "😅", "🤩", "🤗",
        "🙄", "😜", "🤪", "😇", "🤓", "🥳", "😬", "😱", "🤯", "😳",
        "💕", "❤️", "💔", "✨", "🔥", "🌟", "🌈", "☁️", "🌸", "🍀",
        "🍰", "🍜", "☕️", "🎉", "👍", "👏", "🙏", "💪", "🤝", "✌️",
        "🐱", "🐶", "🌝", "💡", "📌", "🎵", "🍻", "🌿", "💤", "🫶"
    )

    /** 内置图片贴纸：assets/stickers/ 下的图片（自动发现）。返回 "asset:文件名"。 */
    fun imageStickers(ctx: Context): List<String> = try {
        ctx.assets.list("stickers")
            ?.filter { it.endsWith(".png", true) || it.endsWith(".webp", true) }
            ?.map { "asset:$it" } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** 用户/AI 添加的图片贴纸（绝对路径），返回 "file:路径"。 */
    fun userStickers(): List<String> = Config.userStickers().map { "file:$it" }

    /** 全部可用贴纸（emoji + 图片），给 AI 挑选时用的提示文本。 */
    fun pickerHint(ctx: Context): String {
        val imgs = imageStickers(ctx) + userStickers()
        val imgHint = if (imgs.isEmpty()) "（暂无图片贴纸）" else imgs.joinToString("、")
        val emoHint = emojiStickers.take(24).joinToString("")
        return "图片贴纸可选：$imgHint；emoji 贴纸示例：$emoHint（也可选其他 emoji）。没有合适的就给空字符串。"
    }

    /** 把贴纸画进视图：emoji 走 textView，图片走 imageView（二选一显示）。 */
    fun apply(sticker: String, imageView: ImageView, emojiView: TextView) {
        imageView.visibility = View.GONE
        emojiView.visibility = View.GONE
        if (sticker.isBlank()) return
        when {
            sticker.startsWith("asset:") -> {
                try {
                    val name = sticker.removePrefix("asset:")
                    val bmp = BitmapFactory.decodeStream(imageView.context.assets.open("stickers/$name"))
                    imageView.setImageBitmap(bmp)
                    imageView.visibility = View.VISIBLE
                } catch (_: Exception) { /* 加载失败则静默隐藏 */ }
            }
            sticker.startsWith("file:") -> {
                val path = sticker.removePrefix("file:")
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) {
                    imageView.setImageBitmap(bmp)
                    imageView.visibility = View.VISIBLE
                }
            }
            else -> {
                emojiView.text = sticker
                emojiView.visibility = View.VISIBLE
            }
        }
    }

    /** 是否为图片贴纸（用于判断 item 是否需要图片视图）。 */
    fun isImageSticker(sticker: String): Boolean =
        sticker.startsWith("asset:") || sticker.startsWith("file:")
}
