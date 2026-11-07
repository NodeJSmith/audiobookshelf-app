package com.audiobookshelf.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

// Within this far into a chapter, "previous" goes to the prior chapter instead of restarting this one
private const val RESTART_CHAPTER_THRESHOLD_MS = 3000L

private const val TAG = "ExternalControl"

/**
 * Playback control for automation apps (Tasker, MacroDroid, adb), sent as broadcasts:
 *
 *   <package>.action.PLAY | PAUSE | TOGGLE          (PLAY/TOGGLE resume the last session when idle)
 *   <package>.action.JUMP_FORWARD | JUMP_BACKWARD   (optional int extra "seconds", else the app's jump setting)
 *   <package>.action.NEXT_CHAPTER | PREVIOUS_CHAPTER
 *
 * <package> is the installed application id, e.g. com.audiobookshelf.app.debug.
 *
 * Send these as broadcasts while the app is running. Android blocks a broadcast from starting the
 * player in the background, so to resume while the app is closed, send the same intent to
 * ExternalControlActivity instead, which runs this briefly in the foreground and closes.
 */
class ExternalControlReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    handleExternalControl(context, intent)
  }

  companion object {
    const val PLAY = "PLAY"
    const val PAUSE = "PAUSE"
    const val TOGGLE = "TOGGLE"
    const val JUMP_FORWARD = "JUMP_FORWARD"
    const val JUMP_BACKWARD = "JUMP_BACKWARD"
    const val NEXT_CHAPTER = "NEXT_CHAPTER"
    const val PREVIOUS_CHAPTER = "PREVIOUS_CHAPTER"
    const val EXTRA_SECONDS = "seconds"
  }
}

fun handleExternalControl(context: Context, intent: Intent) {
  val action = intent.action?.substringAfterLast(".action.") ?: return
  val service = PlayerNotificationService.instance
  val session = service?.currentPlaybackSession
  Log.i(TAG, "Received $action (session loaded=${session != null})")

  if (service == null || session == null) {
    if (action == ExternalControlReceiver.PLAY || action == ExternalControlReceiver.TOGGLE) {
      resumeLastSession(context)
    } else {
      Log.i(TAG, "$action ignored: nothing loaded")
    }
    return
  }

  val seconds = intent.getIntExtra(ExternalControlReceiver.EXTRA_SECONDS, 0)
  when (action) {
    ExternalControlReceiver.PLAY -> service.play()
    ExternalControlReceiver.PAUSE -> service.pause()
    ExternalControlReceiver.TOGGLE -> service.playPause()
    ExternalControlReceiver.JUMP_FORWARD -> if (seconds > 0) service.seekForward(seconds * 1000L) else service.jumpForward()
    ExternalControlReceiver.JUMP_BACKWARD -> if (seconds > 0) service.seekBackward(seconds * 1000L) else service.jumpBackward()
    ExternalControlReceiver.NEXT_CHAPTER -> nextChapterStartMs(service)?.let { service.seekPlayer(it) } ?: Log.i(TAG, "No next chapter")
    ExternalControlReceiver.PREVIOUS_CHAPTER -> service.seekPlayer(previousChapterStartMs(service))
    else -> Log.w(TAG, "Unknown action $action")
  }
}

private fun resumeLastSession(context: Context) {
  val intent = Intent(context, PlayerNotificationService::class.java)
    .setAction(PlayerNotificationService.ACTION_RESUME_LAST)
  try {
    ContextCompat.startForegroundService(context, intent)
  } catch (e: Exception) {
    Log.e(TAG, "Could not start the player to resume the last session", e)
  }
}

private fun nextChapterStartMs(service: PlayerNotificationService): Long? =
  service.currentPlaybackSession?.getNextChapterForTime(service.getCurrentTime())?.startMs

private fun previousChapterStartMs(service: PlayerNotificationService): Long {
  val chapters = service.currentPlaybackSession?.chapters.orEmpty()
  val now = service.getCurrentTime()
  val index = chapters.indexOfFirst { now >= it.startMs && it.endMs > now }
  if (index < 0) return 0L
  val current = chapters[index]
  return if (now - current.startMs > RESTART_CHAPTER_THRESHOLD_MS || index == 0) current.startMs else chapters[index - 1].startMs
}
