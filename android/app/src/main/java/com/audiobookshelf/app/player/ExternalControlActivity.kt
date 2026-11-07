package com.audiobookshelf.app.player

import android.app.Activity
import android.os.Bundle

/**
 * Invisible trampoline for ExternalControlReceiver's actions. While this activity is visible the
 * app counts as foreground, so PLAY/TOGGLE can start the player even when the app was closed.
 */
class ExternalControlActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleExternalControl(this, intent)
    finish()
    overridePendingTransition(0, 0)
  }
}
