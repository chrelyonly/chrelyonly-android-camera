package cn.chrelyonly.chrelyonly_camera.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object WeatherHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private const val AMAP_KEY = "e07ffdf58c06667288417c8008d7ef26"

    suspend fun fetchWeather(
        locationQuery: String = "",
        lat: Double? = null,
        lng: Double? = null
    ): String = withContext(Dispatchers.IO) {

        // 1. 优先使用精确经纬度查询 (支持高德与 Open-Meteo)
        if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
            val weatherByCoords = fetchByCoordinates(lat, lng)
            if (!weatherByCoords.isNullOrEmpty()) {
                return@withContext ensureChinese(weatherByCoords)
            }
        }

        // 2. 尝试从 locationQuery 提取 GPS 坐标 (如 "中国 (GPS: 22.5400, 114.0500)")
        val parsedCoords = parseCoordsFromQuery(locationQuery)
        if (parsedCoords != null) {
            val weatherByCoords = fetchByCoordinates(parsedCoords.first, parsedCoords.second)
            if (!weatherByCoords.isNullOrEmpty()) {
                return@withContext ensureChinese(weatherByCoords)
            }
        }

        // 3. 提取城市/区域名称查询
        val rawCity = extractCityName(locationQuery)
        if (rawCity.isNotEmpty()) {
            // 3A. 高德 API (自动解析 adcode 查询，最准确且保证中文)
            val weatherFromAmap = fetchFromAmapWithGeo(rawCity)
            if (!weatherFromAmap.isNullOrEmpty()) {
                return@withContext ensureChinese(weatherFromAmap)
            }

            val cleanCity = rawCity.removeSuffix("市").removeSuffix("省").trim()

            // 3B. vvhan 天气 API
            val weatherFromVvhan = fetchFromVvhan(cleanCity)
            if (!weatherFromVvhan.isNullOrEmpty()) {
                return@withContext ensureChinese(weatherFromVvhan)
            }

            // 3C. autodevp 天气 API
            val weatherFromAutodevp = fetchFromAutodevp(cleanCity)
            if (!weatherFromAutodevp.isNullOrEmpty()) {
                return@withContext ensureChinese(weatherFromAutodevp)
            }

            // 3D. wttr.in 天气 API (带中文参数与汉化)
            val weatherFromWttr = fetchFromWttr(cleanCity)
            if (!weatherFromWttr.isNullOrEmpty()) {
                return@withContext ensureChinese(weatherFromWttr)
            }
        }

        // 4. 定位失败或没获取到城市时，尝试 IP 粗略定位获取当地天气
        val ipWeather = fetchWeatherByIp()
        if (!ipWeather.isNullOrEmpty()) {
            return@withContext ensureChinese(ipWeather)
        }

        return@withContext getDefaultWeather()
    }

    private fun fetchByCoordinates(lat: Double, lng: Double): String? {
        // A. 高德逆地理编码获取 adcode ➔ 高德实时天气 API (100% 中文)
        try {
            val regeoUrl = "https://restapi.amap.com/v3/geocode/regeo?location=$lng,$lat&key=$AMAP_KEY&extensions=base"
            val request = Request.Builder().url(regeoUrl).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    if (json.optString("status") == "1") {
                        val regeocode = json.optJSONObject("regeocode")
                        val addressComponent = regeocode?.optJSONObject("addressComponent")
                        val adcode = addressComponent?.optString("adcode", "")
                        if (!adcode.isNullOrEmpty() && adcode != "[]") {
                            val weatherFromAmap = fetchFromAmapDirect(adcode)
                            if (!weatherFromAmap.isNullOrEmpty()) {
                                return weatherFromAmap
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // B. Open-Meteo 开源天气 (通过 WMO code 转中文)
        try {
            val openMeteoUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&current=temperature_2m,relative_humidity_2m,weather_code"
            val request = Request.Builder().url(openMeteoUrl).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    val current = json.optJSONObject("current")
                    if (current != null) {
                        val tempVal = current.optDouble("temperature_2m", Double.NaN)
                        val weatherCode = current.optInt("weather_code", 0)
                        val humidity = current.optInt("relative_humidity_2m", -1)
                        if (!tempVal.isNaN()) {
                            val tempStr = "${tempVal.roundToInt()}℃"
                            val (icon, typeStr) = parseWmoWeatherCode(weatherCode)
                            val airInfo = if (humidity > 0) "湿度$humidity%" else "空气优"
                            return "$icon $typeStr $tempStr $airInfo"
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun fetchFromAmapWithGeo(cityName: String): String? {
        try {
            // 1. 先调用高德地理编码获取城市 adcode
            val encodedName = URLEncoder.encode(cityName, "UTF-8")
            val geoUrl = "https://restapi.amap.com/v3/geocode/geo?address=$encodedName&key=$AMAP_KEY"
            val requestGeo = Request.Builder().url(geoUrl).header("User-Agent", "Mozilla/5.0").build()

            var adcode: String? = null
            client.newCall(requestGeo).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    if (json.optString("status") == "1") {
                        val geocodes = json.optJSONArray("geocodes")
                        if (geocodes != null && geocodes.length() > 0) {
                            adcode = geocodes.optJSONObject(0)?.optString("adcode", "")
                        }
                    }
                }
            }

            val targetCode = if (!adcode.isNullOrEmpty() && adcode != "[]") adcode else cityName
            if (targetCode.isNotEmpty()) {
                val weather = fetchFromAmapDirect(targetCode)
                if (!weather.isNullOrEmpty()) {
                    return weather
                }
            }
        } catch (_: Exception) {}

        return fetchFromAmapDirect(cityName)
    }

    private fun fetchFromAmapDirect(cityOrAdcode: String): String? {
        try {
            val encodedCity = URLEncoder.encode(cityOrAdcode, "UTF-8")
            val url = "https://restapi.amap.com/v3/weather/weatherInfo?city=$encodedCity&key=$AMAP_KEY&extensions=base"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    if (json.optString("status") == "1") {
                        val lives = json.optJSONArray("lives")
                        if (lives != null && lives.length() > 0) {
                            val live = lives.optJSONObject(0)
                            val weather = live?.optString("weather", "") ?: ""
                            val temp = live?.optString("temperature", "") ?: ""
                            val humidity = live?.optString("humidity", "") ?: ""

                            if (weather.isNotEmpty() && temp.isNotEmpty()) {
                                val icon = getWeatherIcon(weather)
                                val airInfo = if (humidity.isNotEmpty()) "湿度$humidity%" else "空气优"
                                return "$icon $weather ${temp}℃ $airInfo"
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun fetchFromVvhan(city: String): String? {
        try {
            val encodedCity = URLEncoder.encode(city, "UTF-8")
            val url = "https://api.vvhan.com/api/weather?city=$encodedCity"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string().trim()
                    if (body.isNotEmpty() && body.startsWith("{")) {
                        val json = JSONObject(body)
                        if (json.optBoolean("success")) {
                            val info = json.optJSONObject("info") ?: return null
                            val type = info.optString("type", "")
                            val high = info.optString("high", "").replace("℃", "").replace("高温 ", "").trim()
                            val airObj = info.optJSONObject("air")
                            val airName = airObj?.optString("aqi_name", "优") ?: "优"

                            if (type.isNotEmpty()) {
                                val tempDisplay = if (high.isNotEmpty()) "${high}℃" else ""
                                val icon = getWeatherIcon(type)
                                return if (tempDisplay.isNotEmpty()) "$icon $type $tempDisplay 空气$airName" else "$icon $type 空气$airName"
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun fetchFromAutodevp(city: String): String? {
        try {
            val encodedCity = URLEncoder.encode(city, "UTF-8")
            val url = "http://autodevp.com/api/weather?city=$encodedCity"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string().trim()
                    if (body.isNotEmpty() && body.startsWith("{")) {
                        val json = JSONObject(body)
                        val dataArray = json.optJSONArray("data")
                        val todayData = dataArray?.optJSONObject(0)
                        if (todayData != null) {
                            val weather = todayData.optString("weather", "")
                            val temp = todayData.optString("temp", "").replace("℃", "").trim()
                            val air = todayData.optString("air", "优")
                            if (weather.isNotEmpty()) {
                                val icon = getWeatherIcon(weather)
                                val tempDisplay = if (temp.isNotEmpty()) "${temp}℃" else ""
                                return if (tempDisplay.isNotEmpty()) "$icon $weather $tempDisplay 空气$air" else "$icon $weather 空气$air"
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun fetchFromWttr(city: String): String? {
        try {
            val encodedCity = URLEncoder.encode(city, "UTF-8")
            val url = "https://wttr.in/$encodedCity?format=j1&lang=zh"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string().trim()
                    if (body.isNotEmpty() && body.startsWith("{")) {
                        val json = JSONObject(body)
                        val currentCondition = json.optJSONArray("current_condition")?.optJSONObject(0)
                        if (currentCondition != null) {
                            val tempC = currentCondition.optString("temp_C", "")

                            var weatherDesc = ""
                            val langZhArray = currentCondition.optJSONArray("lang_zh")
                            if (langZhArray != null && langZhArray.length() > 0) {
                                weatherDesc = langZhArray.optJSONObject(0)?.optString("value", "") ?: ""
                            }
                            if (weatherDesc.isEmpty()) {
                                val weatherDescArray = currentCondition.optJSONArray("weatherDesc")
                                val rawDesc = weatherDescArray?.optJSONObject(0)?.optString("value", "") ?: ""
                                weatherDesc = translateWeatherToChinese(rawDesc)
                            } else {
                                weatherDesc = translateWeatherToChinese(weatherDesc)
                            }

                            if (tempC.isNotEmpty() && weatherDesc.isNotEmpty()) {
                                val icon = getWeatherIcon(weatherDesc)
                                return "$icon $weatherDesc ${tempC}℃"
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun fetchWeatherByIp(): String? {
        // A. 百度 IP 定位
        try {
            val request = Request.Builder()
                .url("https://qifu-api.baidubce.com/ip/geo/v1/district")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    if (json.optString("code") == "Success") {
                        val data = json.optJSONObject("data")
                        if (data != null) {
                            val lat = data.optString("lat", "").toDoubleOrNull()
                            val lng = data.optString("lng", "").toDoubleOrNull()
                            if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                                val weather = fetchByCoordinates(lat, lng)
                                if (!weather.isNullOrEmpty()) return weather
                            }

                            val city = data.optString("city", "")
                            if (city.isNotEmpty() && city != "NULL") {
                                val weather = fetchFromAmapWithGeo(city)
                                if (!weather.isNullOrEmpty()) return weather
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // B. ip-api.com 定位
        try {
            val request = Request.Builder()
                .url("http://ip-api.com/json/?lang=zh-CN")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    if (json.optString("status") == "success") {
                        val lat = json.optDouble("lat", Double.NaN)
                        val lon = json.optDouble("lon", Double.NaN)
                        if (!lat.isNaN() && !lon.isNaN()) {
                            val weather = fetchByCoordinates(lat, lon)
                            if (!weather.isNullOrEmpty()) return weather
                        }

                        val city = json.optString("city", "")
                        if (city.isNotEmpty()) {
                            val weather = fetchFromAmapWithGeo(city)
                            if (!weather.isNullOrEmpty()) return weather
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun parseCoordsFromQuery(locationQuery: String): Pair<Double, Double>? {
        if (locationQuery.isEmpty()) return null
        val regex = Regex("([0-9]{1,2}\\.[0-9]+),\\s*([0-9]{1,3}\\.[0-9]+)")
        val match = regex.find(locationQuery) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull()
        val lng = match.groupValues[2].toDoubleOrNull()
        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
            return Pair(lat, lng)
        }
        return null
    }

    private fun extractCityName(locationQuery: String): String {
        if (locationQuery.isEmpty()) return ""
        val clean = locationQuery.replace(Regex("\\(.*?\\)"), "").trim()
        val cityMatch = Regex("([\\u4e00-\\u9fa5]{2,6}市)").find(clean)
        if (cityMatch != null) {
            return cityMatch.value
        }

        val chineseParts = clean.split(" ", "省", "区", "县", "街道")
            .map { it.replace(Regex("[^\\u4e00-\\u9fa5]"), "") }
            .filter { it.length >= 2 }

        return chineseParts.firstOrNull() ?: ""
    }

    private fun getWeatherIcon(type: String): String {
        return when {
            type.contains("晴") -> "☀️"
            type.contains("多云") -> "⛅"
            type.contains("阴") -> "☁️"
            type.contains("雷") -> "🌩️"
            type.contains("雨") -> "🌧️"
            type.contains("雪") -> "❄️"
            type.contains("雾") || type.contains("霾") -> "🌫️"
            else -> "☀️"
        }
    }

    private fun parseWmoWeatherCode(code: Int): Pair<String, String> {
        return when (code) {
            0 -> Pair("☀️", "晴")
            1, 2 -> Pair("🌤️", "多云")
            3 -> Pair("☁️", "阴")
            45, 48 -> Pair("🌫️", "雾")
            51, 53, 55, 56, 57 -> Pair("🌧️", "毛毛雨")
            61, 63 -> Pair("🌧️", "小雨")
            65, 66, 67 -> Pair("🌧️", "大雨")
            71, 73, 75, 77 -> Pair("❄️", "雪")
            80, 81, 82 -> Pair("🌧️", "阵雨")
            85, 86 -> Pair("❄️", "阵雪")
            95, 96, 99 -> Pair("🌩️", "雷阵雨")
            else -> Pair("☀️", "晴")
        }
    }

    private fun translateWeatherToChinese(text: String): String {
        if (text.isEmpty()) return "晴"
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("sun") || lower.contains("clear") || lower.contains("fair") -> "晴"
            lower.contains("partly cloud") -> "多云"
            lower.contains("cloud") -> "多云"
            lower.contains("overcast") -> "阴"
            lower.contains("fog") || lower.contains("mist") || lower.contains("haze") -> "雾"
            lower.contains("thunder") || lower.contains("storm") || lower.contains("lightning") -> "雷阵雨"
            lower.contains("heavy rain") || lower.contains("torrential rain") -> "大雨"
            lower.contains("light rain") || lower.contains("drizzle") || lower.contains("patchy rain") -> "小雨"
            lower.contains("shower") -> "阵雨"
            lower.contains("rain") -> "雨"
            lower.contains("heavy snow") -> "大雪"
            lower.contains("light snow") || lower.contains("sleet") || lower.contains("flurries") -> "小雪"
            lower.contains("snow") || lower.contains("blizzard") || lower.contains("ice") -> "雪"
            lower.contains("wind") -> "大风"
            text.matches(Regex(".*[\\u4e00-\\u9fa5].*")) -> text
            else -> "晴"
        }
    }

    private fun ensureChinese(text: String): String {
        val parts = text.split(" ")
        val cleanedParts = parts.map { part ->
            if (part.matches(Regex("^[a-zA-Z\\s]+$"))) {
                translateWeatherToChinese(part)
            } else {
                part
            }
        }
        return cleanedParts.joinToString(" ")
    }

    private fun getDefaultWeather(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val icon = if (hour in 6..18) "☀️" else "🌙"
        return "$icon 暂无天气"
    }
}
