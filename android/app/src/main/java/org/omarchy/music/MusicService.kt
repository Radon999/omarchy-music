package org.omarchy.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Plays the music: ExoPlayer with the EQ in its audio path, and a media session so the
 *  notification, lock screen, Bluetooth buttons and Android Auto see what's playing.
 *  Next and previous go to [Music]'s queue, which holds one song in the player at a time. */
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Music.init(this)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(Music.eqProcessor))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }.setEnableAudioFloatOutput(true)   // hi-res FLAC stays 24-bit through the EQ
        val exo = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Music.paused = !exo.playWhenReady
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Music.paused = !playWhenReady
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) Music.trackEnded()
                if (state == Player.STATE_READY) exo.duration.takeIf { it > 0 }?.let { Music.duration = it / 1000.0 }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (Music.streaming) Music.streamBroke()
                else {
                    Music.toast("Couldn't play “${Music.current()?.title}”: ${error.errorCodeName}")
                    Music.paused = true
                }
            }
        })
        // The session's buttons: next and previous always there, and they go to our queue.
        val queueAware = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()

            override fun isCommandAvailable(command: Int) = command in setOf(Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) || super.isCommandAvailable(command)

            override fun seekToNext() = Music.next()
            override fun seekToNextMediaItem() = Music.next()
            override fun seekToPrevious() = Music.previous()
            override fun seekToPreviousMediaItem() = Music.previous()
            override fun play() { if (exo.mediaItemCount == 0) Music.togglePause() else super.play() }
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, queueAware).setSessionActivity(open).build()
        Music.player = exo
        Music.playerReady()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away while paused: stop; while playing, the music keeps going.
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        Music.player = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
