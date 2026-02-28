package com.orbit.scheduler;

import com.orbit.service.ConjunctionAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "conjunction.analysis.enabled", havingValue = "true")
public class ConjunctionAnalysisScheduler {

    private final ConjunctionAnalysisService conjunctionAnalysisService;

    @Value("${conjunction.analysis.primary.norad.ids:}")
    private String primaryNoradIdsStr;

    @Value("${conjunction.analysis.cooldown.minutes:60}")
    private int cooldownMinutes;

    private final Map<Integer, LocalDateTime> lastRunByNorad = new ConcurrentHashMap<>();

    public void recordManualRun(Integer noradId) {
        lastRunByNorad.put(noradId, LocalDateTime.now());
        log.debug("Cooldown timer reset for NORAD {} (manual run recorded)", noradId);
    }

    @Scheduled(cron = "${conjunction.analysis.cron:0 0 */6 * * *}")
    public void scheduledConjunctionAnalysis() {
        if (primaryNoradIdsStr == null || primaryNoradIdsStr.trim().isEmpty()) {
            log.warn("No primary NORAD IDs configured for conjunction analysis");
            return;
        }

        List<Integer> primaryNoradIds = Arrays.stream(primaryNoradIdsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .toList();

        log.info("Scheduled conjunction analysis triggered for {} primary satellites",
                primaryNoradIds.size());

        for (Integer noradId : primaryNoradIds) {
            if (!isCooldownExpired(noradId)) {
                continue;
            }

            try {
                log.info("Scheduled analysis starting for NORAD {}", noradId);
                conjunctionAnalysisService.analyzeConjunctions(noradId);
                lastRunByNorad.put(noradId, LocalDateTime.now());
            } catch (Exception e) {
                log.error("Scheduled analysis failed for NORAD {}: {}", noradId, e.getMessage(), e);
            }
        }

        log.info("Scheduled conjunction analysis completed");
    }

    @Scheduled(cron = "${conjunction.cleanup.cron:0 0 2 * * SUN}")
    public void scheduledCleanup() {
        int daysToKeep = 30;
        log.info("Starting scheduled cleanup of conjunction events older than {} days", daysToKeep);
        try {
            conjunctionAnalysisService.cleanupOldEvents(daysToKeep);
            log.info("Scheduled cleanup completed successfully");
        } catch (Exception e) {
            log.error("Failed to cleanup old events: {}", e.getMessage(), e);
        }
    }

    private boolean isCooldownExpired(Integer noradId) {
        if (cooldownMinutes <= 0) {
            return true;
        }

        LocalDateTime lastRun = lastRunByNorad.get(noradId);
        if (lastRun == null) {
            return true;
        }

        long minutesSinceLastRun = ChronoUnit.MINUTES.between(lastRun, LocalDateTime.now());
        if (minutesSinceLastRun < cooldownMinutes) {
            log.info(
                    "Skipping scheduled analysis for NORAD {} — last run was {} minutes ago "
                            + "(cooldown: {} minutes). Next eligible run at {}.",
                    noradId,
                    minutesSinceLastRun,
                    cooldownMinutes,
                    lastRun.plusMinutes(cooldownMinutes)
            );
            return false;
        }

        return true;
    }
}