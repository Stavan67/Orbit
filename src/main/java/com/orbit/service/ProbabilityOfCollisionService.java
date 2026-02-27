package com.orbit.service;

import com.orbit.dto.ConjunctionResult;
import com.orbit.dto.PcResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
@Slf4j
public class ProbabilityOfCollisionService {
    private static final double SIGMA_RADIAL_BASE_M       = 100.0;
    private static final double SIGMA_RADIAL_GROWTH_MPD   = 100.0;   // metres per day
    private static final double SIGMA_CROSS_BASE_M        = 300.0;
    private static final double SIGMA_CROSS_GROWTH_MPD    = 200.0;
    private static final double SIGMA_ALONG_BASE_M        = 1_000.0;
    private static final double SIGMA_ALONG_GROWTH_MPD    = 1_000.0;

    @Value("${conjunction.pc.combined.hbr.m:10.0}")
    private double defaultCombinedHbrM;

    @Value("${conjunction.pc.log.threshold:1e-6}")
    private double logThresholdPc;

    public PcResult computeFromTle(
            ConjunctionResult result,
            LocalDateTime primaryTleEpoch,
            LocalDateTime secondaryTleEpoch
    ) {
        int primaryAgeDays   = tleDaysOld(primaryTleEpoch);
        int secondaryAgeDays = tleDaysOld(secondaryTleEpoch);

        double[] primarySigmas   = estimateSigmas(primaryAgeDays);
        double[] secondarySigmas = estimateSigmas(secondaryAgeDays);

        double sigmaRadial = rss(primarySigmas[0], secondarySigmas[0]);
        double sigmaCross  = rss(primarySigmas[1], secondarySigmas[1]);

        double sigmaX = sigmaRadial;
        double sigmaY = sigmaCross;

        double pc = foster2dPc(result.getMissDistance(), defaultCombinedHbrM, sigmaX, sigmaY);

        if (pc >= logThresholdPc) {
            log.debug(
                    "Pc({}-{}): {} | miss={}m | σ_x={}m σ_y={}m "
                            + "| HBR={}m | TLE-age primary={}d secondary={}d",
                    result.getPrimaryNoradId(), result.getSecondaryNoradId(),
                    String.format("%.3e", pc),
                    String.format("%.0f", result.getMissDistance()),
                    String.format("%.0f", sigmaX),
                    String.format("%.0f", sigmaY),
                    String.format("%.0f", defaultCombinedHbrM),
                    primaryAgeDays,
                    secondaryAgeDays);
        }

        PcResult pcResult = new PcResult();
        pcResult.setPc(pc);
        pcResult.setSigmaXm(sigmaX);
        pcResult.setSigmaYm(sigmaY);
        pcResult.setCombinedHbrM(defaultCombinedHbrM);
        pcResult.setMethod("TLE_AGE_MODEL");
        pcResult.setPrimaryTleAgeDays(primaryAgeDays);
        pcResult.setSecondaryTleAgeDays(secondaryAgeDays);
        return pcResult;
    }

    public PcResult fromCdm(double cdmPc) {
        PcResult result = new PcResult();
        result.setPc(cdmPc);
        result.setMethod("CDM_DIRECT");
        result.setSigmaXm(Double.NaN);
        result.setSigmaYm(Double.NaN);
        result.setCombinedHbrM(Double.NaN);
        return result;
    }

    public PcRiskCategory categorizePc(double pc) {
        if (pc >= 1e-2)   return PcRiskCategory.CRITICAL;
        if (pc >= 1e-3)   return PcRiskCategory.HIGH;
        if (pc >= 1e-4)   return PcRiskCategory.MEDIUM;
        return PcRiskCategory.LOW;
    }

    double foster2dPc(
            double missDistanceM,
            double combinedHbrM,
            double sigmaXm,
            double sigmaYm
    ) {
        if (missDistanceM <= 0 || sigmaXm <= 0 || sigmaYm <= 0) return 0.0;

        double aHbr = Math.PI * combinedHbrM * combinedHbrM;
        double xc = missDistanceM / Math.sqrt(2.0);
        double yc = missDistanceM / Math.sqrt(2.0);

        double exponent = -0.5 * (sq(xc / sigmaXm) + sq(yc / sigmaYm));
        double pc = (aHbr / (2 * Math.PI * sigmaXm * sigmaYm)) * Math.exp(exponent);

        if (pc < 1e-30) return 0.0;

        return clamp(pc, 0.0, 1.0);
    }

    double[] estimateSigmas(int ageDays) {
        double t = Math.max(0, ageDays);
        return new double[]{
                SIGMA_RADIAL_BASE_M + SIGMA_RADIAL_GROWTH_MPD * t,
                SIGMA_CROSS_BASE_M  + SIGMA_CROSS_GROWTH_MPD  * t,
                SIGMA_ALONG_BASE_M  + SIGMA_ALONG_GROWTH_MPD  * t
        };
    }

    private int tleDaysOld(LocalDateTime epoch) {
        if (epoch == null) {
            log.warn("TLE epoch is null — using 7 days as conservative default");
            return 7;
        }
        return (int) Math.max(0, ChronoUnit.DAYS.between(epoch, LocalDateTime.now()));
    }

    private double rss(double a, double b) {
        return Math.sqrt(a * a + b * b);
    }

    private double sq(double x) {
        return x * x;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public enum PcRiskCategory {
        CRITICAL, HIGH, MEDIUM, LOW
    }
}