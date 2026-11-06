package cn.chrelyonly.chrelyonly_camera.model

data class WatermarkConfig(
    val showTime: Boolean = true,
    val showLocation: Boolean = true,
    val showWeather: Boolean = true,
    val weatherText: String = "☀️ 晴 24℃ 空气优",
    val showCustomText: Boolean = true,
    val customText: String = "我的专属相机水印",
    val ratioX: Float = 0.05f,
    val ratioY: Float = 0.85f,
    val isDragged: Boolean = false
)
