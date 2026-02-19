package com.orbit.service;

import com.orbit.dto.ConjunctionResult;
import com.orbit.entity.ConjunctionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@Slf4j
public class RiskAssessmentService {
    public ConjunctionEvent.RiskLevel assessRisk(ConjunctionResult result, LocalDateTime now) {
        double missDistanceKm  = result.getMissDistance() / 1000.0;   // m → km
        double relativeVelocityKmS = result.getRelativeVelocity() / 1000.0; // m/s → km/s

        Duration timeToTca = Duration.between(now, result.getTca());
        long hoursToTca = Math.max(0L, timeToTca.toHours());

        double velocityFactor = Math.min(relativeVelocityKmS / 10.0, 2.0);

        double effectiveMissKm = missDistanceKm / Math.max(velocityFactor, 0.5);

        if (missDistanceKm < 1.0 && hoursToTca < 24) {
            return ConjunctionEvent.RiskLevel.CRITICAL;
        }

        if (missDistanceKm < 2.0 && relativeVelocityKmS > 12.0) {
            return ConjunctionEvent.RiskLevel.CRITICAL;
        }

        if (effectiveMissKm < 5.0 && hoursToTca < 48) {
            return ConjunctionEvent.RiskLevel.HIGH;
        }

        if (missDistanceKm < 2.0) {
            return ConjunctionEvent.RiskLevel.HIGH;
        }

        if (effectiveMissKm < 10.0 && hoursToTca < 72) {
            return ConjunctionEvent.RiskLevel.MEDIUM;
        }

        return ConjunctionEvent.RiskLevel.LOW;
    }

    public boolean requiresAttention(ConjunctionEvent.RiskLevel riskLevel) {
        return riskLevel == ConjunctionEvent.RiskLevel.CRITICAL ||
                riskLevel == ConjunctionEvent.RiskLevel.HIGH;
    }

    public String generateRiskSummary(ConjunctionResult result, ConjunctionEvent.RiskLevel riskLevel) {
        return String.format(
                "[%s] Conjunction between %d and %d: TCA=%s, Miss Distance=%.0fm, Relative Velocity=%.1fm/s",
                riskLevel,
                result.getPrimaryNoradId(),
                result.getSecondaryNoradId(),
                result.getTca(),
                result.getMissDistance(),      // metres
                result.getRelativeVelocity()   // m/s
        );
    }

    public double estimateProbabilityOfCollision(
            double missDistanceM,
            double combinedHardBodyRadiusM,
            double relativeVelocityMpS) {
        if (missDistanceM <= 0 || relativeVelocityMpS <= 0) {
            return 0.0;
        }
        double ratio = combinedHardBodyRadiusM / missDistanceM;
        return Math.min(1.0, ratio * ratio);  // geometric upper bound only
    }
}