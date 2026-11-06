package cn.chrelyonly.chrelyonly_camera.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationHelper {

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationString(context: Context): String = suspendCancellableCoroutine { continuation ->
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val cancellationTokenSource = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    try {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        if (!addresses.isNullOrEmpty()) {
                            val address = addresses[0]
                            val locality = address.locality ?: address.subAdminArea ?: ""
                            val featureName = address.featureName ?: address.thoroughfare ?: ""
                            val text = if (locality.isNotEmpty() || featureName.isNotEmpty()) {
                                "$locality $featureName (GPS: ${String.format(Locale.getDefault(), "%.4f", location.latitude)}, ${String.format(Locale.getDefault(), "%.4f", location.longitude)})"
                            } else {
                                "经纬度: ${String.format(Locale.getDefault(), "%.4f", location.latitude)}, ${String.format(Locale.getDefault(), "%.4f", location.longitude)}"
                            }
                            continuation.resume(text)
                        } else {
                            continuation.resume("经纬度: ${String.format(Locale.getDefault(), "%.4f", location.latitude)}, ${String.format(Locale.getDefault(), "%.4f", location.longitude)}")
                        }
                    } catch (e: Exception) {
                        continuation.resume("经纬度: ${String.format(Locale.getDefault(), "%.4f", location.latitude)}, ${String.format(Locale.getDefault(), "%.4f", location.longitude)}")
                    }
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            val text = "GPS: ${String.format(Locale.getDefault(), "%.4f", lastLoc.latitude)}, ${String.format(Locale.getDefault(), "%.4f", lastLoc.longitude)}"
                            continuation.resume(text)
                        } else {
                            continuation.resume("无法获取位置，请确认已开启GPS定位")
                        }
                    }.addOnFailureListener {
                        continuation.resume("定位失败，请检查定位权限")
                    }
                }
            }
            .addOnFailureListener {
                continuation.resume("定位请求失败，请开启GPS")
            }
    }
}
