package com.orbit.scheduler;

import com.orbit.service.CollisionDetectionService;
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
@ConditionalOnProperty(name = "collision.detection.enabled", havingValue = "true")
public class CollisionDetectionScheduler {

    private final CollisionDetectionService collisionDetectionService;

    @Value("${collision.detection.priority.enabled:false}")
    private boolean priorityEnabled;

    @Scheduled(cron = "${collision.detection.cron:0 0 * * * *}")
    public void scheduledCollisionDetection() {
        log.info("Starting scheduled collision detection at {}", LocalDateTime.now());
        try {
            int lookAheadHours = 48;
            int alertCount = collisionDetectionService.detectCollisions(lookAheadHours);

            log.info("Scheduled collision detection completed. Generated {} alerts", alertCount);
        } catch (Exception e) {
            log.error("Scheduled collision detection failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${collision.detection.priority.cron:0 */15 * * * *}")
    public void priorityCollisionDetection() {
        if (!priorityEnabled) {
            return;
        }

        log.info("Starting priority collision detection at {}", LocalDateTime.now());

        try {
            int lookAheadHours = 6;
            int alertCount = collisionDetectionService.detectCollisions(lookAheadHours);

            log.info("Priority collision detection completed. Generated {} alerts", alertCount);
        } catch (Exception e) {
            log.error("Priority collision detection failed: {}", e.getMessage(), e);
        }
    }
}