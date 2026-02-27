package com.orbit.exception;

import lombok.Getter;

@Getter
public class CorruptTleException extends RuntimeException {

    private final int noradId;
    private final double detectedSpeedMs;
    private final double thresholdMs;

    public CorruptTleException(int noradId, double detectedSpeedMs, double thresholdMs) {
        super(String.format(
                "NORAD %d: physically implausible orbital speed %.0f m/s (threshold: %.0f m/s) — TLE likely corrupt",
                noradId, detectedSpeedMs, thresholdMs));
        this.noradId = noradId;
        this.detectedSpeedMs = detectedSpeedMs;
        this.thresholdMs = thresholdMs;
    }

}