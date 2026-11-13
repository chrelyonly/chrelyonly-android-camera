package cn.chrelyonly.chrelyonly_camera.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.concurrent.TimeUnit

object WeatherHelper {
    // 限制超时时间为 3 秒，防止接口无响应卡主
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun fetchWeather(locationQuery: String = ""): String = withContext(Dispatchers.IO) {
        // 如果传入位置提示非国内或失败，直接返回本地默认天气
        if (locationQuery.contains("仅支持国内地址") || locationQuery.contains("失败") || locationQuery.contains("无法")) {
            return@withContext getDefaultWeather()
        }

        try {
            // 只抓取中文字符构成的城市名（如“北京市”、“深圳市”）
            val city = if (locationQuery.isNotBlank() && !locationQuery.contains("正在")) {
                locationQuery.split(" ")
                    .firstOrNull { part -> part.any { it in '\u4e00'..'\u9fa5' } }
                    ?.replace(Regex("[^\\u4e00-\\u9fa5]"), "") ?: ""
            } else {
                ""
            }

            if (city.isEmpty()) {
                return@withContext getDefaultWeather()
            }

            val url = "https://wttr.in/$city?lang=zh&format=%c+%t+%C"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "curl")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()?.trim()
                    if (!body.isNullOrEmpty() && !body.contains("Unknown") && !body.contains("<html>")) {
                        val chineseWeather = translateToChineseStyle(body)
                        // 确保只输出含有中文/正常符号的结果
                        if (chineseWeather.any { it in '\u4e00'..'\u9fa5' }) {
                            return@withContext chineseWeather
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext getDefaultWeather()
    }

    private fun getDefaultWeather(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val icon = if (hour in 6..18) "☀️ 晴" else "🌙 夜间"
        return "$icon 24℃ 空气优"
    }

    private fun translateToChineseStyle(raw: String): String {
        var text = raw
        val map = mapOf(
            "Sunny" to "晴",
            "Clear" to "晴",
            "Partly cloudy" to "多云",
            "Cloudy" to "多云",
            "Overcast" to "阴",
            "Mist" to "雾",
            "Fog" to "大雾",
            "Patchy rain possible" to "小雨",
            "Light rain" to "小雨",
            "Moderate rain" to "中雨",
            "Heavy rain" to "大雨",
            "Thundery outbreaks possible" to "雷阵雨",
            "Snow" to "雪"
        )
        for ((eng, chi) in map) {
            text = text.replace(eng, chi, ignoreCase = true)
        }

        text = text.replace("+", "")
        text = text.replace("°C", "℃")

        return "$text 空气优"
    }
}
