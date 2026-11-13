package cn.chrelyonly.chrelyonly_camera.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

object LocationHelper {

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationString(context: Context): String = withContext(Dispatchers.IO) {
        // 1. 设置定位总超时时间为 4 秒，避免长时间卡死在“获取位置中”
        val location = withTimeoutOrNull(4000L) {
            getLocationWithFallback(context)
        }

        if (location == null) {
            return@withContext "无法获取位置，请确认已开启GPS"
        }

        val latStr = String.format(Locale.CHINA, "%.4f", location.latitude)
        val lngStr = String.format(Locale.CHINA, "%.4f", location.longitude)

        // 2. 逆地理编码增加 2.5 秒超时保护
        val addressText = withTimeoutOrNull(2500L) {
            parseAddress(context, location)
        }

        addressText ?: "经纬度: $latStr, $lngStr"
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLocationWithFallback(context: Context): Location? {
        // 优先使用 FusedLocationProviderClient 获取当前位置或缓存位置
        val fusedLocation = suspendCancellableCoroutine<Location?> { continuation ->
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                val cts = CancellationTokenSource()
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            continuation.resume(loc)
                        } else {
                            fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                                continuation.resume(lastLoc)
                            }.addOnFailureListener {
                                continuation.resume(null)
                            }
                        }
                    }
                    .addOnFailureListener { continuation.resume(null) }
            } catch (e: Exception) {
                continuation.resume(null)
            }
        }

        if (fusedLocation != null) return fusedLocation

        // 回退：使用系统原生 LocationManager 获取最后已知位置
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val networkLoc = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val gpsLoc = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            networkLoc ?: gpsLoc
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAddress(context: Context, location: Location): String? {
        return try {
            val geocoder = Geocoder(context, Locale.CHINA)
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val countryCode = address.countryCode ?: ""
                val countryName = address.countryName ?: ""

                // 强制只允许国内/中国地址
                if (countryCode.isNotEmpty() && countryCode != "CN" && !countryName.contains("中国")) {
                    return "仅支持国内地址 (GPS: ${String.format(Locale.CHINA, "%.4f", location.latitude)}, ${String.format(Locale.CHINA, "%.4f", location.longitude)})"
                }

                val adminArea = address.adminArea ?: ""
                val locality = address.locality ?: address.subAdminArea ?: ""
                val subLocality = address.subLocality ?: ""
                val featureName = address.featureName ?: address.thoroughfare ?: ""

                val cityPart = if (locality.isNotEmpty()) locality else adminArea
                val detailPart = if (subLocality.isNotEmpty() && subLocality != locality) {
                    "$subLocality $featureName".trim()
                } else {
                    featureName
                }

                val combined = "$cityPart $detailPart".trim()
                val latStr = String.format(Locale.CHINA, "%.4f", location.latitude)
                val lngStr = String.format(Locale.CHINA, "%.4f", location.longitude)

                if (combined.isNotEmpty()) {
                    "$combined (GPS: $latStr, $lngStr)"
                } else {
                    "经纬度: $latStr, $lngStr"
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
