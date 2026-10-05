package app.ft.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import app.ft.core.CarAudioBus
import app.ft.core.DiagLog
import app.ft.core.PcmPacer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object CarPlayer {
    data class State(
        val track: Track? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val index: Int = -1,
        val count: Int = 0,
        val shuffle: Boolean = false,
        val repeat: Int = Player.REPEAT_MODE_OFF,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val toCar: Boolean = false
    )

    private const val CAR_RATE = 48000
    private const val CAR_BYTES_PER_SECOND = CAR_RATE * 4
    private val tag = "Player"
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var player: ExoPlayer? = null
    private val tracks = HashMap<String, Track>()
    @Volatile private var channels = 2
    @Volatile private var rate = CAR_RATE
    @Volatile private var sentToCar = 0L
    var onTrack: ((Track) -> Unit)? = null
    var onBreak: ((String) -> Unit)? = null
    private val pacer = PcmPacer(CAR_BYTES_PER_SECOND, 150, 2000, 3840, "player") { pcm ->
        sentToCar += pcm.size
        CarAudioBus.write(CarAudioBus.LANE_PLAYER, pcm)
    }

    @Volatile
    var toCar = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) pacer.clear()
            main.post {
                applyVolume()
                publish()
            }
        }

    val playing: Boolean get() = _state.value.playing
    val hasTrack: Boolean get() = _state.value.track != null

    private val ticker = object : Runnable {
        override fun run() {
            publish()
            if (player?.isPlaying == true) main.postDelayed(this, 500)
        }
    }

    private val tee = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            if (sampleRateHz != rate && sampleRateHz != CAR_RATE) DiagLog.w(tag, "player sound reaches the car path at $sampleRateHz Hz, converting it on the way")
            rate = sampleRateHz
            channels = channelCount
            if (encoding != C.ENCODING_PCM_16BIT) DiagLog.w(tag, "player sound is not 16-bit pcm ($encoding), the car may not get it")
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!toCar) return
            val n = buffer.remaining()
            if (n <= 0) return
            val raw = ByteArray(n)
            buffer.get(raw)
            val stereo = when {
                channels == 2 -> raw
                channels == 1 -> CarAudioBus.toCarFormat(raw, CAR_RATE, 1)
                else -> firstTwo(raw, channels)
            }
            pacer.add(if (rate == CAR_RATE) stereo else CarAudioBus.toCarFormat(stereo, rate, 2))
        }
    })

    private class CarRate(private val sonic: SonicAudioProcessor = SonicAudioProcessor()) : AudioProcessor by sonic {
        override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            sonic.setOutputSampleRateHz(CAR_RATE)
            val out = sonic.configure(inputAudioFormat)
            if (inputAudioFormat.sampleRate != CAR_RATE) DiagLog.i(tag, "player sound at ${inputAudioFormat.sampleRate} Hz, resampled to $CAR_RATE Hz for the car")
            return out
        }

        override fun getDurationAfterProcessorApplied(durationUs: Long): Long = sonic.getDurationAfterProcessorApplied(durationUs)
    }

    private fun firstTwo(pcm: ByteArray, count: Int): ByteArray {
        val frames = pcm.size / (2 * count)
        val out = ByteArray(frames * 4)
        for (f in 0 until frames) System.arraycopy(pcm, f * 2 * count, out, f * 4, 4)
        return out
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun key(t: Track) = (if (t.video) "v" else "a") + t.id

    private fun ensure(context: Context): ExoPlayer {
        player?.let { return it }
        val app = context.applicationContext
        val renderers = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(CarRate(), tee))
                    .build()
            }
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_SYSTEM)
            .build()
        val p = ExoPlayer.Builder(app, renderers)
            .setAudioAttributes(attributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish()
                if (isPlaying) {
                    main.removeCallbacks(ticker)
                    main.post(ticker)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                pacer.hold(!playWhenReady)
                if (!playWhenReady) onBreak?.invoke("a pause")
                publish()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                publish()
                val t = tracks[mediaItem?.mediaId] ?: return
                DiagLog.i(tag, "playing '${t.title}'${if (t.artist.isNotBlank()) " by ${t.artist}" else ""}${if (toCar) " to the car" else " on the phone"}")
                onTrack?.invoke(t)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) = publish()
            override fun onPlaybackStateChanged(playbackState: Int) = publish()
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = publish()
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()
            override fun onRepeatModeChanged(repeatMode: Int) = publish()

            override fun onPlayerError(error: PlaybackException) {
                DiagLog.w(tag, "could not play this one: ${error.errorCodeName}")
                if (p.hasNextMediaItem()) {
                    pacer.clear()
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
            }
        })
        player = p
        applyVolume()
        return p
    }

    private fun applyVolume() {
        player?.volume = if (toCar) 0f else 1f
    }

    private fun publish() {
        val p = player ?: return
        val t = tracks[p.currentMediaItem?.mediaId]
        val d = p.duration
        val v = p.videoSize
        _state.value = State(
            track = t,
            playing = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = if (d > 0) d else t?.durationMs ?: 0,
            index = p.currentMediaItemIndex,
            count = p.mediaItemCount,
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
            videoWidth = if (t?.video == true) (v.width * v.pixelWidthHeightRatio).toInt() else 0,
            videoHeight = if (t?.video == true) v.height else 0,
            toCar = toCar
        )
    }

    fun play(context: Context, list: List<Track>, start: Int) = onMain {
        if (list.isEmpty()) return@onMain
        val p = ensure(context)
        pacer.clear()
        tracks.clear()
        list.forEach { tracks[key(it)] = it }
        p.setMediaItems(list.map { MediaItem.Builder().setUri(it.uri).setMediaId(key(it)).build() }, start.coerceIn(0, list.lastIndex), 0)
        p.prepare()
        p.play()
    }

    fun toggle() = onMain {
        val p = player ?: return@onMain
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) p.pause() else resume(p)
    }

    fun resume() = onMain { player?.let { if (!it.isPlaying) resume(it) } }

    private fun resume(p: ExoPlayer) {
        if (p.mediaItemCount == 0) return
        if (p.playbackState == Player.STATE_ENDED) {
            pacer.clear()
            p.seekTo(0, 0)
        }
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    fun pause() = onMain { player?.pause() }

    fun next() = onMain {
        val p = player ?: return@onMain
        if (p.hasNextMediaItem()) {
            pacer.clear()
            p.seekToNextMediaItem()
        }
    }

    fun previous() = onMain {
        val p = player ?: return@onMain
        pacer.clear()
        if (p.currentPosition > 3000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) = onMain {
        val p = player ?: return@onMain
        pacer.clear()
        onBreak?.invoke("a jump in the song")
        p.seekTo(ms.coerceAtLeast(0))
    }

    fun setShuffle(on: Boolean) = onMain { player?.shuffleModeEnabled = on }

    fun cycleRepeat() = onMain {
        val p = player ?: return@onMain
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stop() = onMain {
        if (player?.mediaItemCount ?: 0 > 0) DiagLog.i(tag, "stopped")
        pacer.clear()
        player?.stop()
        player?.clearMediaItems()
        tracks.clear()
        _state.value = State(toCar = toCar)
    }

    fun stopVideo() = onMain {
        if (_state.value.track?.video == true) stop()
    }

    fun setVideoSurface(surface: Surface?) = onMain {
        val p = player ?: return@onMain
        if (surface == null) p.clearVideoSurface() else p.setVideoSurface(surface)
    }

    fun clearVideoSurface(surface: Surface) = onMain { player?.clearVideoSurface(surface) }

    fun takeSent(): Long {
        val n = sentToCar
        sentToCar = 0
        return n
    }
}
