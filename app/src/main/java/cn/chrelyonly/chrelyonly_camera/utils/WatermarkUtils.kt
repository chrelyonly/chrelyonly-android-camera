package cn.chrelyonly.chrelyonly_camera.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import cn.chrelyonly.chrelyonly_camera.model.WatermarkConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WatermarkUtils {

    fun applyWatermarkAndSave(
        context: Context,
        sourceBitmap: Bitmap,
        config: WatermarkConfig,
        locationText: String
    ): Uri? {
        val mutableBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val width = mutableBitmap.width
        val height = mutableBitmap.height

        val paint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textSize = (width / 35f).coerceAtLeast(28f)
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        val backgroundPaint = Paint().apply {
            color = Color.parseColor("#80000000")
            isAntiAlias = true
        }

        val lines = mutableListOf<String>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 星期E", Locale.CHINA)
        if (config.showTime) {
            lines.add("🕒 ${dateFormat.format(Date())}")
        }
        if (config.showLocation) {
            lines.add("📍 $locationText")
        }
        if (config.showWeather && config.weatherText.isNotEmpty()) {
            lines.add(config.weatherText)
        }
        if (config.showCustomText && config.customText.isNotEmpty()) {
            lines.add("🏷️ ${config.customText}")
        }

        if (lines.isEmpty()) return saveBitmapToMediaStore(context, mutableBitmap)

        val padding = 24f
        val lineSpacing = 12f
        var maxTextWidth = 0f
        var totalTextHeight = 0f

        val bounds = Rect()
        for (line in lines) {
            paint.getTextBounds(line, 0, line.length, bounds)
            if (bounds.width() > maxTextWidth) {
                maxTextWidth = bounds.width().toFloat()
            }
            totalTextHeight += bounds.height() + lineSpacing
        }

        val boxWidth = maxTextWidth + padding * 2
        val boxHeight = totalTextHeight + padding * 2 - lineSpacing

        val maxAllowedX = (width - boxWidth).coerceAtLeast(0f)
        val maxAllowedY = (height - boxHeight).coerceAtLeast(0f)

        val startX = if (config.ratioX >= 0f) {
            (config.ratioX * maxAllowedX).coerceIn(0f, maxAllowedX)
        } else {
            maxAllowedX / 2f
        }

        val startY = if (config.ratioY >= 0f) {
            (config.ratioY * maxAllowedY).coerceIn(0f, maxAllowedY)
        } else {
            maxAllowedY / 2f
        }

        canvas.drawRoundRect(
            startX,
            startY,
            startX + boxWidth,
            startY + boxHeight,
            16f,
            16f,
            backgroundPaint
        )

        var currentY = startY + padding + paint.textSize
        for (line in lines) {
            canvas.drawText(line, startX + padding, currentY, paint)
            paint.getTextBounds(line, 0, line.length, bounds)
            currentY += bounds.height() + lineSpacing
        }

        return saveBitmapToMediaStore(context, mutableBitmap)
    }

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap): Uri? {
        val filename = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChrelyonlyCamera")
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }
        return uri
    }
}
