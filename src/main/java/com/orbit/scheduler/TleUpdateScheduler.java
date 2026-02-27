package com.orbit.scheduler;

import com.orbit.service.CdmIngestionService;
import com.orbit.service.SpaceTrackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "tle.update.enabled", havingValue = "true")
public class TleUpdateScheduler {

    private final SpaceTrackService spaceTrackService;
    private final CdmIngestionService cdmIngestionService;

    @Value("${tle.priority.norad.ids:}")
    private String priorityNoradIdsStr;

    @Value("${cdm.fetch.enabled:true}")
    private boolean cdmFetchEnabled;

    @Scheduled(initialDelay = 60_000, fixedDelay = Long.MAX_VALUE)
    public void fetchOnStartup() {
        log.info("Startup: fetching latest TLEs and CDMs");
        try {
            spaceTrackService.fetchAndSaveLatestTles();
            fetchCdmsForPriorityIfEnabled();
            log.info("Startup fetch completed");
        } catch (Exception e) {
            log.error("Startup fetch failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${tle.update.cron:0 30 0 * * *}")
    public void scheduledTleUpdate() {
        log.info("Daily TLE update starting at {}", LocalDateTime.now());
        try {
            spaceTrackService.fetchAndSaveLatestTles();
            fetchCdmsForPriorityIfEnabled();
            log.info("Daily TLE update completed");
        } catch (Exception e) {
            log.error("Daily TLE update failed: {}", e.getMessage(), e);
        }
    }

    private void fetchCdmsForPriorityIfEnabled() {
        if (!cdmFetchEnabled) return;
        List<Integer> priorityIds = parsePriorityIds();
        if (priorityIds.isEmpty()) return;
        try {
            String authCookie = spaceTrackService.getOrRefreshAuthCookie();
            cdmIngestionService.fetchAndStoreCdmsForSatellites(priorityIds, authCookie);
        } catch (Exception e) {
            log.error("CDM fetch failed: {}", e.getMessage(), e);
        }
    }

    private List<Integer> parsePriorityIds() {
        if (priorityNoradIdsStr == null || priorityNoradIdsStr.isBlank()) return List.of();
        return Arrays.stream(priorityNoradIdsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }
}