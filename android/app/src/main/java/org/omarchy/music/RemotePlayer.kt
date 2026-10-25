package org.omarchy.music

import android.os.Looper
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the desktop is playing, as a player for the media session: while the phone is its
 *  remote, the notification and the lock screen show the desktop's song, and their buttons
 *  go to the desktop. It plays nothing itself. */
class RemotePlayer(looper: Looper) : SimpleBasePlayer(looper) {
    private var watching: Job? = null
    private var artFor = ""
    private var art: ByteArray? = null

    init {
        watching = Music.scope.launch {
            snapshotFlow { Remote.status }.collect { s ->
                val t = s.track
                if (t != null && t.id != artFor) {
                    artFor = t.id; art = null
                    Music.bg({ Remote.artBytes(t) }) { bytes, _ -> if (artFor == t.id) { art = bytes; invalidateState() } }
                }
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val s = Remote.status
        val t = s.track
        val b = State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build())
        if (t == null) return b.setPlaybackState(Player.STATE_IDLE).build()
        val meta = MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
            .apply { art?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }.build()
        val item = MediaItem.Builder().setMediaId(t.id).setMediaMetadata(meta).build()
        val duration = if (s.duration > 0) s.duration else t.duration
        return b.setPlaylist(listOf(MediaItemData.Builder(t.id).setMediaItem(item).setIsSeekable(true)
                .apply { if (duration > 0) setDurationUs((duration * 1e6).toLong()) }.build()))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(!s.paused, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs { (Remote.livePosition() * 1000).toLong() }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Remote.cmd(if (playWhenReady) "play" else "pause")
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> Remote.cmd("next")
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> Remote.cmd("prev")
            else -> if (positionMs >= 0) Remote.cmd("seek", "position" to positionMs / 1000.0)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        Remote.cmd("pause")
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        watching?.cancel()
        return Futures.immediateVoidFuture()
    }
}
