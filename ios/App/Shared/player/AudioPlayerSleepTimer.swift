//
//  AudioPlayerSleepTimer.swift
//  App
//
//  Created by Ron Heft on 9/2/22.
//

import Foundation
import AVFoundation

// A timer left paused this long is cleared, so one set at bedtime isn't still armed the next day
private let sleepTimerPausedExpiration: TimeInterval = 60 * 60

extension AudioPlayer {
    
    // MARK: - Public API
    
    public func isSleepTimerSet() -> Bool {
        return self.isCountdownSleepTimerSet() || self.isChapterSleepTimerSet()
    }
    
    public func getSleepTimeRemaining() -> Double? {
        guard let currentTime = self.getCurrentTime() else { return nil }
        
        // Return the player time until sleep
        var sleepTimeRemaining: Double? = nil
        if let chapterStopAt = self.sleepTimeChapterStopAt {
            sleepTimeRemaining = (chapterStopAt - currentTime) / Double(self.rateManager.rate > 0 ? self.rateManager.rate : 1.0)
        } else if self.isCountdownSleepTimerSet() {
            sleepTimeRemaining = self.sleepTimeRemaining
        }
        
        return sleepTimeRemaining
    }
    
    public func setSleepTimer(secondsUntilSleep: Double) {
        AbsLogger.info(message: "SLEEP TIMER: Sleeping in \(secondsUntilSleep) seconds")
        self.removeSleepTimer()
        self.sleepTimeRemaining = secondsUntilSleep
        self.sleepTimerLastPlayedAt = Date()

        DispatchQueue.runOnMainQueue {
            self.sleepTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                if self?.isPlaying() ?? false {
                    self?.decrementSleepTimerIfRunning()
                } else {
                    self?.expireSleepTimerIfPausedTooLong()
                }
            }
        }
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
    }
    
    public func setChapterSleepTimer(stopAt: Double?) {
        self.removeSleepTimer()
        guard let stopAt = stopAt else { return }
        guard let currentTime = self.getCurrentTime() else { return }
        guard stopAt >= currentTime else { return }
        
        AbsLogger.info(message: "SLEEP TIMER: Scheduling for chapter end \(stopAt)")
        
        // Schedule the observation time
        self.sleepTimeChapterStopAt = stopAt
        self.sleepTimerLastPlayedAt = Date()

        // Get the current track
        guard let playbackSession = self.getPlaybackSession() else { return }
        let currentTrack = playbackSession.audioTracks[currentTrackIndex]

        // Set values
        guard let trackStartTime = currentTrack.startOffset else { return }
        guard let trackEndTime = currentTrack.endOffset else { return }

        // Verify the stop is during the current audio track
        guard trackEndTime >= stopAt else { return }

        // Schedule the observation time
        let trackBasedStopTime = stopAt - trackStartTime
        
        let sleepTime = CMTime(seconds: trackBasedStopTime, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        var times = [NSValue]()
        times.append(NSValue(time: sleepTime))
        
        self.sleepTimeChapterToken = self.audioPlayer.addBoundaryTimeObserver(forTimes: times, queue: self.queue) { [weak self] in
            self?.handleSleepEnd()
        }
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
    }
    
    public func increaseSleepTime(extraTimeInSeconds: Double) {
        self.removeChapterSleepTimer()
        guard let sleepTimeRemaining = self.sleepTimeRemaining else { return }
        self.sleepTimeRemaining = sleepTimeRemaining + extraTimeInSeconds
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
    }
    
    public func decreaseSleepTime(removeTimeInSeconds: Double) {
        self.removeChapterSleepTimer()
        guard let sleepTimeRemaining = self.sleepTimeRemaining else { return }
        self.sleepTimeRemaining = sleepTimeRemaining - removeTimeInSeconds
        
        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepSet.rawValue), object: nil)
    }
    
    public func removeSleepTimer() {
        self.sleepTimer?.invalidate()
        self.sleepTimer = nil
        self.removeChapterSleepTimer()
        self.sleepTimeRemaining = nil
        self.sleepTimerLastPlayedAt = nil

        // Update the UI
        NotificationCenter.default.post(name: NSNotification.Name(PlayerEvents.sleepEnded.rawValue), object: self)
    }


    // MARK: - Internal helpers

    /// Clears a set timer that hasn't seen playback for `sleepTimerPausedExpiration`, so a timer set
    /// at bedtime and paused overnight doesn't stop playback the next morning.
    internal func expireSleepTimerIfPausedTooLong() {
        guard self.isSleepTimerSet(), let lastPlayedAt = self.sleepTimerLastPlayedAt else { return }
        guard Date().timeIntervalSince(lastPlayedAt) >= sleepTimerPausedExpiration else { return }
        AbsLogger.info(message: "SLEEP TIMER: Paused over \(Int(sleepTimerPausedExpiration / 60)) min, clearing it")
        self.removeSleepTimer()
    }

    internal func handleTrackChangeForChapterSleepTimer() {
        if self.isChapterSleepTimerSet() {
            self.setChapterSleepTimer(stopAt: self.sleepTimeChapterStopAt)
        }
    }
    
    private func decrementSleepTimerIfRunning() {
        if var sleepTimeRemaining = self.sleepTimeRemaining {
            sleepTimeRemaining -= 1
            self.sleepTimeRemaining = sleepTimeRemaining
          
            if sleepTimeRemaining == 60 && self.isSleepTimerFadeOutEnabled() {
                self.startFadeOut()
            }
          
            // Handle the sleep if the timer has expired
            if sleepTimeRemaining <= 0 {
                self.handleSleepEnd()
            }
        }
    }
    
    private func handleSleepEnd() {
        AbsLogger.info(message: "SLEEP TIMER: Pausing audio")
        self.pause()
        self.removeSleepTimer()
    }
    
    private func removeChapterSleepTimer() {
        if let token = self.sleepTimeChapterToken {
            self.audioPlayer.removeTimeObserver(token)
        }
        self.sleepTimeChapterToken = nil
        self.sleepTimeChapterStopAt = nil
    }
    
    private func isCountdownSleepTimerSet() -> Bool {
        return self.sleepTimeRemaining != nil
    }
    
    private func isChapterSleepTimerSet() -> Bool {
        return self.sleepTimeChapterStopAt != nil
    }
  
    private func isSleepTimerFadeOutEnabled() -> Bool {
      let deviceSettings = Database.shared.getDeviceSettings()
      return !deviceSettings.disableSleepTimerFadeOut
    }
}
