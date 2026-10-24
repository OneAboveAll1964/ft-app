package app.ft.aa

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

/** Crops the negotiated AA margins without enlarging the touchable view. */
class AaVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var video = AaVideoLayout.forFrame(800, 480, 800, 480)
    private var output: Surface? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    fun setVideoLayout(layout: AaVideoLayout) {
        if (video == layout) return
        video = layout
        surfaceTexture?.setDefaultBufferSize(video.frameWidth, video.frameHeight)
        transformPicture()
    }

    private fun transformPicture() {
        setTransform(Matrix().apply {
            setScale(video.scaleX, video.scaleY, width / 2f, height / 2f)
        })
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(video.frameWidth, video.frameHeight)
        transformPicture()
        output = Surface(texture).also { AaVideoSink.attach(it, video.frameWidth, video.frameHeight) }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(video.frameWidth, video.frameHeight)
        transformPicture()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        AaVideoSink.detach(output)
        output?.release()
        output = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}
