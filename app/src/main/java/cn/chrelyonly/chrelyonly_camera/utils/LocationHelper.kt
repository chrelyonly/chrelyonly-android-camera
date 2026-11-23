package cn.chrelyonly.chrelyonly_camera.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LocationResult(
    val addressString: String,
    val latitude: Double? = null,
    val longitude: Double? = null
)

object LocationHelper {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationDetails(context: Context): LocationResult = withContext(Dispatchers.IO) {
        // 1. 设置定位总超时时间为 8 秒，保障 GPS / 网络定位有足够时间获取基站或卫星信号
        val location = withTimeoutOrNull(8000L) {
            getLocationWithFallback(context)
        }

        if (location != null) {
            val latStr = String.format(Locale.CHINA, "%.4f", location.latitude)
            val lngStr = String.format(Locale.CHINA, "%.4f", location.longitude)

            // 2. 逆地理编码增加 4 秒超时保护
            val addressText = withTimeoutOrNull(4000L) {
                parseAddress(context, location)
            }

            if (!addressText.isNullOrEmpty()) {
                return@withContext LocationResult(
                    addressString = addressText,
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            }
            return@withContext LocationResult(
                addressString = "中国 (GPS: $latStr, $lngStr)",
                latitude = location.latitude,
                longitude = location.longitude
            )
        }

        // 3. 当设备处于无GMS/室内无法接收GPS信号时，使用国内IP粗略定位作为兜底
        val ipLocation = withTimeoutOrNull(3000L) {
            getIpLocationDomestic()
        }

        LocationResult(
            addressString = ipLocation ?: "无法获取位置，请确认已开启GPS与网络"
        )
    }

    suspend fun getCurrentLocationString(context: Context): String {
        return getCurrentLocationDetails(context).addressString
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLocationWithFallback(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        // Step 1: 遍历所有 Provider 获取最佳缓存定位
        val cachedLoc = getBestCachedLocation(locationManager)
        // 如果缓存定位在 15 分钟内，且有效，直接使用
        if (cachedLoc != null && (System.currentTimeMillis() - cachedLoc.time < 900_000L)) {
            return cachedLoc
        }

        // Step 2: 尝试 FusedLocationProviderClient (GMS/HMS) 与原生 LocationManager 并行获取
        return suspendCancellableCoroutine { continuation ->
            var isResumed = false
            fun resumeOnce(loc: Location?) {
                if (!isResumed) {
                    isResumed = true
                    continuation.resume(loc)
                }
            }

            // A. FusedLocationProviderClient
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                val cts = CancellationTokenSource()
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            resumeOnce(loc)
                        } else {
                            fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                                lastLoc?.let { resumeOnce(it) }
                            }
                        }
                    }
                    .addOnFailureListener {
                        // ignore failure, wait for LocationManager
                    }
            } catch (_: Exception) {}

            // B. 原生 LocationManager (GPS + NETWORK)
            if (locationManager != null) {
                try {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                            resumeOnce(location)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }

                    var hasRequested = false
                    @Suppress("DEPRECATION")
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
                        hasRequested = true
                    }
                    @Suppress("DEPRECATION")
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                        hasRequested = true
                    }

                    if (!hasRequested && cachedLoc != null) {
                        resumeOnce(cachedLoc)
                    }
                } catch (_: Exception) {
                    cachedLoc?.let { resumeOnce(it) }
                }
            } else {
                cachedLoc?.let { resumeOnce(it) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBestCachedLocation(locationManager: LocationManager?): Location? {
        val locations = mutableListOf<Location>()

        if (locationManager != null) {
            try {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { locations.add(it) }
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { locations.add(it) }
                locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.let { locations.add(it) }
            } catch (_: Exception) {}
        }

        // 挑选最新且有效的位置
        return locations.asSequence()
            .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .maxByOrNull { it.time }
    }

    private fun parseAddress(context: Context, location: Location): String? {
        val lat = location.latitude
        val lng = location.longitude

        // 转换为中国 GCJ-02 (火星坐标系) 坐标，供国内高德/百度逆地理编码接口精确查询
        val gcj02 = CoordinateConverter.wgs84ToGcj02(lat, lng)
        val gcjLat = gcj02[0]
        val gcjLng = gcj02[1]

        // 1. 优先使用国内高德逆地理编码 API (基于 GCJ-02)
        val amapAddress = parseAddressAmap(gcjLat, gcjLng)
        if (!amapAddress.isNullOrEmpty()) {
            return amapAddress
        }

        // 2. 尝试百度地图逆地理编码 API
        val baiduAddress = parseAddressBaidu(lat, lng)
        if (!baiduAddress.isNullOrEmpty()) {
            return baiduAddress
        }

        // 3. 尝试 Android 系统 Geocoder (中文 Locale)
        try {
            val geocoder = Geocoder(context, Locale.CHINA)
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val line0 = address.getAddressLine(0) ?: ""
                val clean = formatDomesticAddress(
                    line0,
                    address.adminArea,
                    address.locality,
                    address.subLocality,
                    address.thoroughfare ?: address.featureName,
                )

                if (clean.isNotEmpty()) {
                    return clean
                }
            }
        } catch (_: Exception) {}

        // 4. 降级使用 OpenStreetMap 在线逆地理编码
        val osmAddress = parseAddressOsm(lat, lng)
        if (!osmAddress.isNullOrEmpty()) {
            return osmAddress
        }

        return null
    }

    private fun parseAddressAmap(lat: Double, lng: Double): String? {
        return try {
            // 高德 Web 服务公共 API Key
            val url = "https://restapi.amap.com/v3/geocode/regeo?location=$lng,$lat&key=e07ffdf58c06667288417c8008d7ef26&extensions=all"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    if (json.optString("status") == "1") {
                        val regeocode = json.optJSONObject("regeocode") ?: return null
                        val formatted = regeocode.optString("formatted_address", "")
                        if (formatted.isNotEmpty() && formatted != "[]") {
                            var clean = formatted.trim()
                            if (clean.startsWith("中国")) clean = clean.removePrefix("中国").trim()
                            return clean
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAddressBaidu(lat: Double, lng: Double): String? {
        return try {
            val url = "https://api.map.baidu.com/geocoder/v2/?location=$lat,$lng&output=json&pois=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    if (json.optInt("status") == 0) {
                        val result = json.optJSONObject("result") ?: return null
                        val formatted = result.optString("formatted_address", "")
                        val sematicDescription = result.optString("sematic_description", "")
                        var full = formatted
                        if (sematicDescription.isNotEmpty()) {
                            full = "$formatted $sematicDescription"
                        }
                        if (full.isNotEmpty()) {
                            var clean = full.trim()
                            if (clean.startsWith("中国")) clean = clean.removePrefix("中国").trim()
                            return clean
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAddressOsm(lat: Double, lng: Double): String? {
        return try {
            val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&accept-language=zh-CN"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ChrelyonlyCameraApp/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    val addressObj = json.optJSONObject("address") ?: return null

                    val province = addressObj.optString("state", addressObj.optString("province", ""))
                    val city = addressObj.optString("city", addressObj.optString("town", addressObj.optString("county", "")))
                    val district = addressObj.optString("suburb", addressObj.optString("city_district", addressObj.optString("district", "")))
                    val road = addressObj.optString("road", addressObj.optString("pedestrian", ""))

                    val full = formatDomesticAddress("", province, city, district, road)
                    full.ifEmpty { null }
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatDomesticAddress(
        fullLine: String,
        adminArea: String?,
        locality: String?,
        subLocality: String?,
        featureName: String?
    ): String {
        var text = fullLine.trim()

        if (text.startsWith("中国")) {
            text = text.removePrefix("中国").trim()
        } else if (text.startsWith("China")) {
            text = text.removePrefix("China").trim()
        }

        if (text.isNotEmpty() && !text.contains("Unmapped") && !text.contains("Unknown")) {
            return text
        }

        val admin = (adminArea ?: "").removePrefix("中国").trim()
        val city = if (!locality.isNullOrEmpty()) locality else ""
        val district = subLocality ?: ""
        val feature = featureName ?: ""

        val sb = StringBuilder()
        if (admin.isNotEmpty()) sb.append(admin)
        if (city.isNotEmpty() && city != admin) sb.append(city)
        if (district.isNotEmpty() && district != city) sb.append(district)
        if (feature.isNotEmpty() && feature != district && feature != city && feature != admin) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(feature)
        }

        return sb.toString().trim()
    }

    private fun getIpLocationDomestic(): String? {
        // 1. 尝试百度高精度 IP 区县定位接口
        try {
            val request = Request.Builder()
                .url("https://qifu-api.baidubce.com/ip/geo/v1/district")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    if (json.optString("code") == "Success") {
                        val data = json.optJSONObject("data")
                        if (data != null) {
                            val prov = data.optString("prov", "")
                            val city = data.optString("city", "")
                            val district = data.optString("district", "")

                            val sb = StringBuilder()
                            if (prov.isNotEmpty() && prov != "NULL") sb.append(prov)
                            if (city.isNotEmpty() && city != "NULL" && city != prov) sb.append(city)
                            if (district.isNotEmpty() && district != "NULL") sb.append(district)

                            if (sb.isNotEmpty()) {
                                return "$sb (网络定位)"
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. 尝试太平洋 PConline IP 定位
        try {
            val request = Request.Builder()
                .url("http://whois.pconline.com.cn/ipJson.jsp?json=true")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body.bytes()
                    val body = String(bytes, Charset.forName("GBK"))
                    val json = JSONObject(body)
                    val pro = json.optString("pro", "")
                    val city = json.optString("city", "")
                    val region = json.optString("addr", "")

                    if (pro.isNotEmpty() || city.isNotEmpty()) {
                        val clean = if (region.isNotEmpty()) region.removePrefix("中国").trim() else "$pro$city"
                        return "$clean (网络定位)"
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. 降级 ip-api.com
        try {
            val request = Request.Builder()
                .url("http://ip-api.com/json/?lang=zh-CN")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    if (json.optString("status") == "success") {
                        val regionName = json.optString("regionName", "")
                        val city = json.optString("city", "")
                        return "$regionName $city (网络定位)".trim()
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * WGS-84 (GPS标准) 坐标转 GCJ-02 (中国火星坐标系) 转换器
     */
    private object CoordinateConverter {
        private const val A = 6378245.0
        private const val EE = 0.00669342162296594323

        fun wgs84ToGcj02(lat: Double, lon: Double): DoubleArray {
            if (outOfChina(lat, lon)) {
                return doubleArrayOf(lat, lon)
            }
            var dLat = transformLat(lon - 105.0, lat - 35.0)
            var dLon = transformLon(lon - 105.0, lat - 35.0)
            val radLat = lat / 180.0 * PI
            var magic = sin(radLat)
            magic = 1 - EE * magic * magic
            val sqrtMagic = sqrt(magic)
            dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
            dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
            val mgLat = lat + dLat
            val mgLon = lon + dLon
            return doubleArrayOf(mgLat, mgLon)
        }

        private fun outOfChina(lat: Double, lon: Double): Boolean {
            if (lon !in 72.004..137.8347) return true
            return lat !in 0.8293..55.8271
        }

        private fun transformLat(x: Double, y: Double): Double {
            var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
            ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
            ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
            ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
            return ret
        }

        private fun transformLon(x: Double, y: Double): Double {
            var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
            ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
            ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
            ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
            return ret
        }
    }
}
