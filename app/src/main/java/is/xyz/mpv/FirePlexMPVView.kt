package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

class FirePlexMPVView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var initialized = false
    private var pendingUrl: String? = null
    private var pendingSubtitleUrl: String? = null
    private var zoomMode: String = "fill"
    private var volumePercent: Int = 100

    fun initializePlayer(
        configDir: String,
        cacheDir: String,
        preBufferMs: Int,
        hardwareDecoder: Boolean,
        h264Level: String,
        zoom: String,
        volume: Int
    ) {
        if (initialized) return

        zoomMode = zoom
        volumePercent = volume.coerceIn(0, 100)

        MPVLib.create(context.applicationContext)
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("config-dir", configDir)
        MPVLib.setOptionString("gpu-shader-cache-dir", cacheDir)
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        val compatibilityMode = h264Level != "auto"
        MPVLib.setOptionString("hwdec", if (hardwareDecoder && !compatibilityMode) "mediacodec-copy,mediacodec" else "no")
        MPVLib.setOptionString("vd-lavc-check-hw-profile", if (compatibilityMode) "no" else "yes")
        MPVLib.setOptionString("vd-lavc-software-fallback", "yes")
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("demuxer-readahead-secs", (preBufferMs.coerceAtLeast(300) / 1000.0).toString())
        MPVLib.setOptionString("demuxer-max-bytes", "${96 * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${24 * 1024 * 1024}")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "once")
        MPVLib.setOptionString("input-default-bindings", "yes")
        MPVLib.init()

        initialized = true
        holder.addCallback(this)
        applyVolume(volumePercent)
        applyZoom(zoomMode)
    }

    fun load(playUrl: String, subtitleUrl: String?) {
        pendingUrl = playUrl
        pendingSubtitleUrl = subtitleUrl
        if (initialized && holder.surface.isValid) {
            MPVLib.command(arrayOf("loadfile", playUrl, "replace"))
            addSubtitleIfNeeded(subtitleUrl)
            applyVolume(volumePercent)
            applyZoom(zoomMode)
        }
    }

    fun destroyPlayer() {
        if (!initialized) return
        runCatching { holder.removeCallback(this) }
        runCatching { MPVLib.command(arrayOf("stop")) }
        runCatching { MPVLib.detachSurface() }
        MPVLib.destroy()
        initialized = false
    }

    fun setPaused(paused: Boolean) {
        if (initialized) MPVLib.setPropertyBoolean("pause", paused)
    }

    fun isPaused(): Boolean {
        return if (initialized) MPVLib.getPropertyBoolean("pause") ?: false else false
    }

    fun seekBy(seconds: Int) {
        if (initialized) MPVLib.command(arrayOf("seek", seconds.toString(), "relative"))
    }

    fun seekToMs(ms: Long) {
        if (initialized) MPVLib.command(arrayOf("seek", (ms / 1000.0).toString(), "absolute"))
    }

    fun positionMs(): Long {
        return (((if (initialized) MPVLib.getPropertyDouble("time-pos") else null) ?: 0.0) * 1000.0)
            .toLong()
            .coerceAtLeast(0L)
    }

    fun durationMs(): Long {
        return (((if (initialized) MPVLib.getPropertyDouble("duration") else null) ?: 0.0) * 1000.0)
            .toLong()
            .coerceAtLeast(0L)
    }

    fun applyVolume(volume: Int) {
        volumePercent = volume.coerceIn(0, 100)
        if (initialized) MPVLib.setPropertyInt("volume", volumePercent)
    }

    fun applyZoom(zoom: String) {
        zoomMode = zoom
        if (!initialized) return

        MPVLib.setPropertyBoolean("video-unscaled", false)
        MPVLib.setPropertyBoolean("keepaspect", true)
        MPVLib.setPropertyString("video-aspect-override", "-1")
        MPVLib.setPropertyDouble("video-zoom", 0.0)

        when (zoomMode) {
            "16:9" -> MPVLib.setPropertyString("video-aspect-override", "16:9")
            "4:3" -> MPVLib.setPropertyString("video-aspect-override", "4:3")
            "original" -> MPVLib.setPropertyBoolean("video-unscaled", true)
            "fill" -> MPVLib.setPropertyBoolean("keepaspect", false)
            "zoom" -> MPVLib.setPropertyDouble("video-zoom", 0.25)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!initialized) return
        MPVLib.attachSurface(holder.surface)
        MPVLib.setPropertyString("force-window", "yes")
        pendingUrl?.let { load(it, pendingSubtitleUrl) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (initialized) MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!initialized) return
        runCatching { MPVLib.setPropertyString("force-window", "no") }
        runCatching { MPVLib.detachSurface() }
    }

    private fun addSubtitleIfNeeded(subtitleUrl: String?) {
        if (!subtitleUrl.isNullOrBlank()) {
            MPVLib.command(arrayOf("sub-add", subtitleUrl, "select"))
        }
    }
}
