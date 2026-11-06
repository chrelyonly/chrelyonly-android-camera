package cn.chrelyonly.chrelyonly_camera.ui

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import cn.chrelyonly.chrelyonly_camera.model.WatermarkConfig
import cn.chrelyonly.chrelyonly_camera.utils.LocationHelper
import cn.chrelyonly.chrelyonly_camera.utils.WeatherHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _watermarkConfig = MutableStateFlow(WatermarkConfig())
    val watermarkConfig: StateFlow<WatermarkConfig> = _watermarkConfig.asStateFlow()

    private val _realLocationText = MutableStateFlow("正在获取位置...")
    private val _customLocationText = MutableStateFlow<String?>(null)
    val customLocationText: StateFlow<String?> = _customLocationText.asStateFlow()

    private val _locationText = MutableStateFlow("正在获取位置...")
    val locationText: StateFlow<String> = _locationText.asStateFlow()

    private val _flashMode = MutableStateFlow(0)
    val flashMode: StateFlow<Int> = _flashMode.asStateFlow()

    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing: StateFlow<Int> = _lensFacing.asStateFlow()

    private val _showGrid = MutableStateFlow(false)
    val showGrid: StateFlow<Boolean> = _showGrid.asStateFlow()

    private val _timerSeconds = MutableStateFlow(0)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()

    private val _lastCapturedUri = MutableStateFlow<Uri?>(null)
    val lastCapturedUri: StateFlow<Uri?> = _lastCapturedUri.asStateFlow()

    init {
        refreshLocation()
        loadLatestPhotoUri()
    }

    private fun updateDisplayLocation() {
        val custom = _customLocationText.value
        _locationText.value = if (!custom.isNullOrEmpty()) custom else _realLocationText.value
    }

    fun refreshLocation() {
        if (!_customLocationText.value.isNullOrEmpty()) {
            return
        }
        _realLocationText.value = "正在重新获取位置..."
        updateDisplayLocation()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val loc = LocationHelper.getCurrentLocationString(getApplication())
                _realLocationText.value = loc
                updateDisplayLocation()
                refreshWeather(loc)
            } catch (e: Exception) {
                _realLocationText.value = "定位失败"
                updateDisplayLocation()
            }
        }
    }

    fun refreshWeather(locationQuery: String = "") {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val weather = WeatherHelper.fetchWeather(locationQuery)
                val current = _watermarkConfig.value
                _watermarkConfig.value = current.copy(weatherText = weather)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setCustomLocation(custom: String?) {
        _customLocationText.value = custom
        updateDisplayLocation()
        if (!custom.isNullOrEmpty()) {
            refreshWeather(custom)
        }
    }

    fun toggleTimer() {
        _timerSeconds.value = when (_timerSeconds.value) {
            0 -> 3
            3 -> 5
            else -> 0
        }
    }

    private fun loadLatestPhotoUri() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                getApplication<Application>().contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val id = cursor.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        _lastCapturedUri.value = uri
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateWatermarkConfig(newConfig: WatermarkConfig) {
        _watermarkConfig.value = newConfig
    }

    fun updateWatermarkDrag(ratioX: Float, ratioY: Float) {
        val current = _watermarkConfig.value
        _watermarkConfig.value = current.copy(ratioX = ratioX, ratioY = ratioY, isDragged = true)
    }

    fun toggleFlash() {
        _flashMode.value = (_flashMode.value + 1) % 3
    }

    fun toggleCamera() {
        _lensFacing.value = if (_lensFacing.value == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }

    fun toggleGrid() {
        _showGrid.value = !_showGrid.value
    }

    fun setLastCapturedUri(uri: Uri?) {
        _lastCapturedUri.value = uri
    }
}
