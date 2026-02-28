package com.orbit.service;

import com.orbit.dto.ConjunctionResult;
import com.orbit.dto.PcResult;
import com.orbit.entity.ConjunctionEvent.RiskLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskAssessmentService {
    public RiskLevel assessRisk(
            ConjunctionResult result,
            PcResult pcResult,
            LocalDateTime now
    ) {
        RiskLevel pcRisk = classifyByPc(pcResult.getPc());
        RiskLevel geometricRisk = classifyByGeometry(result, now);

        RiskLevel finalRisk = higher(pcRisk, geometricRisk);

        if (finalRisk == RiskLevel.CRITICAL || finalRisk == RiskLevel.HIGH) {
            log.debug(
                    "Risk assessment for {}-{}: Pc={} → {} | geometric → {} | final={}",
                    result.getPrimaryNoradId(),
                    result.getSecondaryNoradId(),
                    pcResult.getPcFormatted(),
                    pcRisk,
                    geometricRisk,
                    finalRisk);
        }

        return finalRisk;
    }

    public RiskLevel assessRiskFromPcOnly(double pc, ConjunctionResult result, LocalDateTime now) {
        RiskLevel pcRisk       = classifyByPc(pc);
        RiskLevel geometricRisk = classifyByGeometry(result, now);
        return higher(pcRisk, geometricRisk);
    }

    private RiskLevel classifyByPc(double pc) {
        if (pc >= 1e-2) return RiskLevel.CRITICAL;
        if (pc >= 1e-3) return RiskLevel.HIGH;
        if (pc >= 1e-4) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    private RiskLevel classifyByGeometry(ConjunctionResult result, LocalDateTime now) {
        double missDistanceKm     = result.getMissDistance() / 1000.0;
        double relativeVelocityKms = result.getRelativeVelocity() / 1000.0;

        Duration timeToTca  = Duration.between(now, result.getTca());
        long hoursToTca     = Math.max(0L, timeToTca.toHours());

        double velocityFactor  = Math.min(relativeVelocityKms / 10.0, 2.0);
        double effectiveMissKm = missDistanceKm / Math.max(velocityFactor, 0.5);

        if (missDistanceKm < 1.0 && hoursToTca < 24)               return RiskLevel.CRITICAL;
        if (missDistanceKm < 2.0 && relativeVelocityKms > 12.0)    return RiskLevel.CRITICAL;
        if (effectiveMissKm < 5.0 && hoursToTca < 48)              return RiskLevel.HIGH;
        if (missDistanceKm < 2.0)                                   return RiskLevel.HIGH;
        if (effectiveMissKm < 10.0 && hoursToTca < 72)             return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    private RiskLevel higher(RiskLevel a, RiskLevel b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    public boolean requiresAttention(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.CRITICAL || riskLevel == RiskLevel.HIGH;
    }

    public String generateRiskSummary(ConjunctionResult result, RiskLevel riskLevel, PcResult pcResult) {
        return String.format(
                "[%s] Conjunction %d↔%d | TCA=%s | Miss=%.0fm | RelVel=%.1fm/s | Pc=%s (%s)",
                riskLevel,
                result.getPrimaryNoradId(),
                result.getSecondaryNoradId(),
                result.getTca(),
                result.getMissDistance(),
                result.getRelativeVelocity(),
                pcResult.getPcFormatted(),
                pcResult.getMethod());
    }
}