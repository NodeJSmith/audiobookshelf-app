package com.audiobookshelf.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTrackTest {
  private fun track(index: Int, startOffset: Double, duration: Double, title: String = "Track") =
    AudioTrack(
      index = index,
      startOffset = startOffset,
      duration = duration,
      title = title,
      contentUrl = "/track.mp3",
      mimeType = "audio/mpeg",
      metadata = null,
      isLocal = false,
      localFileId = null,
      serverIndex = null
    )

  @Test
  fun offsetsConvertSecondsToMilliseconds() {
    val audioTrack = track(index = 1, startOffset = 61.5, duration = 30.25)

    assertEquals(61_500L, audioTrack.startOffsetMs)
    assertEquals(30_250L, audioTrack.durationMs)
    assertEquals(91_750L, audioTrack.endOffsetMs)
  }

  @Test
  fun bookChapterIsOneBasedAndSpansTheTrack() {
    val chapter = track(index = 0, startOffset = 0.0, duration = 120.0, title = "Opening").getBookChapter()

    assertEquals(BookChapter(1, 0.0, 120.0, "Opening"), chapter)
  }

  @Test
  fun relPathIsEmptyWithoutMetadata() {
    assertEquals("", track(index = 0, startOffset = 0.0, duration = 1.0).relPath)
  }
}
