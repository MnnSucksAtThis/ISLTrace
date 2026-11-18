package com.example.islteachingapp

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout

/**
 * Loops the configured real-signer clip from the asset path supplied by signs.csv.
 *
 * A TextureView is used because the bundled reference clips have the opposite handedness
 * from the learner-facing convention: right-handed learners see them flipped, while
 * left-handed learners see them in their stored orientation.
 */
class ReferenceVideoPlayer(
    private val context: Context,
    private val view: TextureView,
    private val videoAssetPath: String,
    leftHanded: Boolean,
    private val onError: () -> Unit
) : TextureView.SurfaceTextureListener {

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var active = false
    private var speed = 1f

    init {
        view.scaleX = if (leftHanded) 1f else -1f
        view.surfaceTextureListener = this
    }

    fun start(playbackSpeed: Float) {
        speed = playbackSpeed
        active = true
        if (view.isAvailable && surface == null) {
            view.surfaceTexture?.let { surface = Surface(it) }
        }
        if (surface != null) open()
    }

    fun setSpeed(playbackSpeed: Float) {
        speed = playbackSpeed
        applySpeed()
    }

    /** Frees the player. Safe to call more than once. */
    fun stop() {
        active = false
        player?.release()
        player = null
    }

    private fun open() {
        if (player != null) return
        try {
            val mp = MediaPlayer()
            player = mp
            context.assets.openFd(videoAssetPath).use {
                mp.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setOnVideoSizeChangedListener { _, w, h -> fitToContainer(w, h) }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Could not play $videoAssetPath: $what / $extra")
                onError()
                true
            }
            mp.setOnPreparedListener {
                if (active && player === it) {
                    it.start()
                    applySpeed()
                }
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the clip $videoAssetPath", e)
            stop()
            onError()
        }
    }

    private fun applySpeed() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) mp.playbackParams = PlaybackParams().setSpeed(speed)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Speed $speed is not supported on this phone", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Player was not ready for a speed change", e)
        }
    }

    /** Largest size that shows the whole clip at its true shape, centred in the parent. */
    private fun fitToContainer(videoW: Int, videoH: Int) {
        val parent = view.parent as? FrameLayout ?: return
        if (videoW == 0 || videoH == 0 || parent.width == 0 || parent.height == 0) return
        val scale = minOf(parent.width.toFloat() / videoW, parent.height.toFloat() / videoH)
        view.layoutParams = FrameLayout.LayoutParams(
            (videoW * scale).toInt(), (videoH * scale).toInt(), Gravity.CENTER
        )
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(texture)
        if (active) open()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val wasActive = active
        stop()
        active = wasActive          // reopen if the surface comes back while the panel is still open
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    companion object {
        private const val TAG = "ISL_TEACH"

    }
}
