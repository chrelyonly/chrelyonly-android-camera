package cn.chrelyonly.chrelyonly_camera.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar

object WeatherHelper {
    private val client = OkHttpClient()

    suspend fun fetchWeather(locationQuery: String = ""): String = withContext(Dispatchers.IO) {
        try {
            val city = if (locationQuery.isNotBlank() && !locationQuery.contains("正在") && !locationQuery.contains("失败")) {
                locationQuery.split(" ").firstOrNull()?.replace(Regex("[^\\u4e00-\\u9fa5]"), "") ?: ""
            } else {
                ""
            }

            val url = if (city.isNotEmpty()) {
                "https://wttr.in/$city?lang=zh&format=%c+%t+%C"
            } else {
                "https://wttr.in/?lang=zh&format=%c+%t+%C"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "curl")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()?.trim()
                    if (!body.isNullOrEmpty() && !body.contains("Unknown") && !body.contains("<html>")) {
                        return@withContext translateToChineseStyle(body)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val icon = if (hour in 6..18) "☀️ 晴" else "🌙 夜间"
        "$icon 24℃ 空气优"
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
