package com.cortex.cortex_common.model;

public enum PlaybackStatusEnum {
  // Normalization has not resolved yet — a playback version may still arrive.
  PENDING,
  // A playable object exists at playbackObjectName.
  READY,
  // Normalization failed — no playback version will ever exist for this file.
  UNAVAILABLE
}
