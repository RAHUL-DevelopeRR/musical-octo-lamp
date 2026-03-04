package com.luminaplayer.player;

/**
 * Represents the current state of media playback.
 */
public enum PlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    STOPPED,
    BUFFERING,
    ERROR
}
