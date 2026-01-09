package com.orbit.scheduler;

import com.orbit.service.SpaceTrackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "tle.update.enabled", havingValue = "true")
public class TleUpdateScheduler {

    private final SpaceTrackService spaceTrackService;

    @Value("${tle.critical.update.enabled:false}")
    private boolean criticalUpdateEnabled;

    @Scheduled(initialDelay = 60000, fixedDelay = Long.MAX_VALUE)
    public void fetchOnStartup() {
        log.info("Application startup: Fetching latest TLE data");

        try {
            spaceTrackService.fetchAndSaveLatestTles();
            log.info("Startup TLE fetch completed successfully");
        } catch (Exception e) {
            log.error("Startup TLE fetch failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${tle.update.cron:0 0 */6 * * *}")
    public void scheduledTleUpdate() {
        log.info("Starting scheduled TLE update at {}", LocalDateTime.now());

        try {
            spaceTrackService.fetchAndSaveLatestTles();
            log.info("Scheduled TLE update completed successfully");
        } catch (Exception e) {
            log.error("Scheduled TLE update failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${tle.critical.update.cron:0 0 */2 * * *}")
    public void scheduledCriticalSatellitesUpdate() {
        if (!criticalUpdateEnabled) {
            return;
        }

        log.info("Starting critical satellites TLE update at {}", LocalDateTime.now());

        try {
            Integer[] criticalSatellites = {25544, 48274, 43013};

            for (Integer noradId : criticalSatellites) {
                spaceTrackService.fetchAndSaveTleByNoradId(noradId);
            }

            log.info("Critical satellites TLE update completed successfully");
        } catch (Exception e) {
            log.error("Critical satellites TLE update failed: {}", e.getMessage(), e);
        }
    }
}