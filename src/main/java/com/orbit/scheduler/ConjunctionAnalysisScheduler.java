package com.orbit.scheduler;

import com.orbit.service.ConjunctionAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "conjunction.analysis.enabled", havingValue = "true")
public class ConjunctionAnalysisScheduler {
    private final ConjunctionAnalysisService  conjunctionAnalysisService;

    @Value("${conjunction.analysis.primary.norad.ids:}")
    private String primaryNoradIdsStr;

    @Scheduled(cron = "${conjunction.analysis.cron:0 0 */6 * * *}")
    public void scheduledConjunctionAnalysis() {
        if(primaryNoradIdsStr == null || primaryNoradIdsStr.trim().isEmpty()) {
            log.warn("No primary NORAD IDs configured for conjunction analysis");
            return;
        }

        List<Integer> primaryNoradIds = Arrays.stream(primaryNoradIdsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .toList();

        log.info("Starting scheduled conjunction analysis for {} primary satellites",  primaryNoradIds.size());

        for(Integer noradId : primaryNoradIds) {
            try {
                log.info("Analyzing conjunctions for NORAD ID: {}", noradId);
                conjunctionAnalysisService.analyzeConjunctions(noradId);
            } catch (Exception e) {
                log.error("Failed to analyze conjunctions for NORAD {}: {}", noradId, e.getMessage(),e);
            }
        }
        log.info("Scheduled conjunction analysis completed");
    }

    @Scheduled(cron = "${conjunction.cleanup.cron:0 0 2 * * SUN}")
    public void scheduledCleanup() {
        int daysToKeep = 30;
        log.info("Starting scheduled cleanup of conjunction events older than {} days",  daysToKeep);

        try{
            conjunctionAnalysisService.cleanupOldEvents(daysToKeep);
            log.info("Scheduled cleanup completed successfully");
        } catch(Exception e){
            log.error("Failed to cleanup old events: {}", e.getMessage(),e);
        }
    }
}
