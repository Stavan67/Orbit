package com.orbit.controller;

import com.orbit.entity.CollisionAlert;
import com.orbit.service.CollisionDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/collision")
@RequiredArgsConstructor
@Slf4j
public class CollisionController {
    private final CollisionDetectionService collisionDetectionService;
    @PostMapping("/detect")
    public ResponseEntity<Map<String, Object>> detectCollisions(
            @RequestParam(defaultValue = "24") int lookAheadHours) {
        try {
            log.info("Running collision detection for {} hours", lookAheadHours);
            int alertCount = collisionDetectionService.detectCollisions(lookAheadHours);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Collision detection completed",
                    "alertsGenerated", alertCount,
                    "lookAheadHours", lookAheadHours
            ));
        } catch (Exception e) {
            log.error("Error running collision detection", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "status", "error",
                            "message", "Collision detection failed: " + e.getMessage()
                    ));
        }
    }

    @GetMapping("/check/{satellite1Id}/{satellite2Id}")
    public ResponseEntity<?> checkSatellitePair(
            @PathVariable Long satellite1Id,
            @PathVariable Long satellite2Id,
            @RequestParam(defaultValue = "24") int lookAheadHours) {
        try {
            CollisionAlert alert = collisionDetectionService.checkSatellitePair(
                    satellite1Id, satellite2Id, lookAheadHours
            );

            if (alert != null) {
                return ResponseEntity.ok(Map.of(
                        "status", "collision_risk",
                        "alert", alert
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "status", "no_risk",
                        "message", "No collision risk detected in the specified timeframe"
                ));
            }
        } catch (Exception e) {
            log.error("Error checking satellite pair", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "status", "error",
                            "message", e.getMessage()
                    ));
        }
    }

    @GetMapping("/distance/{satellite1Id}/{satellite2Id}")
    public ResponseEntity<?> getCurrentDistance(
            @PathVariable Long satellite1Id,
            @PathVariable Long satellite2Id) {
        try {
            double distance = collisionDetectionService.getCurrentDistance(
                    satellite1Id, satellite2Id
            );

            return ResponseEntity.ok(Map.of(
                    "satellite1Id", satellite1Id,
                    "satellite2Id", satellite2Id,
                    "distanceKm", distance
            ));
        } catch (Exception e) {
            log.error("Error calculating distance", e);
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "status", "error",
                            "message", e.getMessage()
                    ));
        }
    }

    @GetMapping("/alerts")
    public ResponseEntity<List<CollisionAlert>> getActiveAlerts() {
        try {
            List<CollisionAlert> alerts = collisionDetectionService.getActiveAlerts();
            return ResponseEntity.ok(alerts);
        } catch (Exception e) {
            log.error("Error fetching alerts", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/alerts/{level}")
    public ResponseEntity<List<CollisionAlert>> getAlertsByLevel(
            @PathVariable String level) {
        try {
            List<CollisionAlert> alerts = collisionDetectionService.getAlertsByLevel(
                    level.toUpperCase()
            );
            return ResponseEntity.ok(alerts);
        } catch (Exception e) {
            log.error("Error fetching alerts by level", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/alerts/{alertId}/resolve")
    public ResponseEntity<Map<String, String>> resolveAlert(
            @PathVariable Long alertId) {
        try {
            collisionDetectionService.resolveAlert(alertId);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Alert marked as resolved"
            ));
        } catch (Exception e) {
            log.error("Error resolving alert", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "status", "error",
                            "message", e.getMessage()
                    ));
        }
    }
}