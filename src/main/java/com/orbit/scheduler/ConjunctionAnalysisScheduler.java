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

    /**
     * Minimum minutes that must have passed since the last analysis
     * (manual OR scheduled) before the scheduler will run again.
     *
     * Default: 60 minutes. This prevents the scheduler from immediately
     * re-running after you've just triggered a manual analysis via the API.
     *
     * Set to 0 to disable the cooldown entirely (not recommended in production).
     *
     * Configure in application.properties:
     *   conjunction.analysis.cooldown.minutes=60
     */
    @Value("${conjunction.analysis.cooldown.minutes:60}")
    private int cooldownMinutes;

    /**
     * Tracks the last time analysis ran for each NORAD ID.
     * ConcurrentHashMap because the scheduler thread and HTTP request threads
     * (manual API calls) can both update this simultaneously.
     *
     * This is in-memory only — resets on restart, which is fine. A fresh
     * analysis on startup is not harmful.
     */
    private final Map<Integer, LocalDateTime> lastRunByNorad = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Public method — called by ConjunctionController for manual API triggers
    // so the cooldown map stays in sync with both manual and scheduled runs.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Records that an analysis was just completed for a given NORAD ID.
     * Called by {@link com.orbit.controller.ConjunctionController} after
     * every successful manual analysis so the scheduler cooldown is respected.
     */
    public void recordManualRun(Integer noradId) {
        lastRunByNorad.put(noradId, LocalDateTime.now());
        log.debug("Cooldown timer reset for NORAD {} (manual run recorded)", noradId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scheduled jobs
    // ─────────────────────────────────────────────────────────────────────────

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
                continue; // skip — logged inside isCooldownExpired
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

    // ─────────────────────────────────────────────────────────────────────────
    // Cooldown check
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if enough time has passed since the last analysis run
     * for the given NORAD ID (either manual or scheduled).
     *
     * If cooldownMinutes = 0, always returns true (cooldown disabled).
     */
    private boolean isCooldownExpired(Integer noradId) {
        if (cooldownMinutes <= 0) {
            return true;
        }

        LocalDateTime lastRun = lastRunByNorad.get(noradId);
        if (lastRun == null) {
            return true; // never run — proceed
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