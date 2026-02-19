package com.orbit.controller;

import com.orbit.entity.ConjunctionEvent;
import com.orbit.service.ConjunctionAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conjunction")
@RequiredArgsConstructor
@Slf4j
public class ConjunctionController {
    private final ConjunctionAnalysisService conjunctionAnalysisService;
    @PostMapping("/analyze/{noradId}")
    public ResponseEntity<?> analyzeConjunctions(@PathVariable Integer noradId) {
        try {
            log.info("Received request to analyze conjunctions for noradId {}", noradId);
            List<ConjunctionEvent> events = conjunctionAnalysisService.analyzeConjunctions(noradId);

            long criticalCount = events.stream()
                    .filter(e -> e.getRiskLevel() == ConjunctionEvent.RiskLevel.CRITICAL)
                    .count();
            long highCount = events.stream()
                    .filter(e -> e.getRiskLevel() == ConjunctionEvent.RiskLevel.HIGH)
                    .count();

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "noradId", noradId,
                    "totalEvents", events.size(),
                    "criticalEvents", criticalCount,
                    "highRiskEvents", highCount,
                    "message", String.format("Found %d conjunction events (%d critical, %d high risk)",
                            events.size(), criticalCount, highCount)
            ));
        } catch (Exception e) {
            log.error("Error analyzing conjunctions for NORAD {}: ", noradId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "status", "error",
                            "message", "Failed to analyze conjunctions: " + e.getMessage()
                    ));
        }
    }

    @GetMapping("/upcoming/{noradId}")
    public ResponseEntity<?> getUpcomingEvents(@PathVariable Integer noradId, @RequestParam(defaultValue = "7") int days) {
        try {
            log.info("Fetching upcoming events for NORAD {} (next {} days)", noradId, days);
            List<ConjunctionEvent> events = conjunctionAnalysisService.getUpcomingEvents(noradId, days);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "noradId", noradId,
                    "daysAhead", days,
                    "eventCount", events.size(),
                    "events", events
            ));
        } catch (Exception e) {
            log.error("Error fetching upcoming events for NORAD {}: ",  noradId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "status", "error",
                            "message", "Failed to fetch events: " + e.getMessage()
                    ));
        }
    }

    @GetMapping("/high-risk/{noradId}")
    public ResponseEntity<?> getHighRiskEvents(@PathVariable Integer noradId) {
        try {
            log.info("Fetching high risk events for NORAD {}", noradId);
            List<ConjunctionEvent> events = conjunctionAnalysisService.getHighRiskEvents(noradId);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "noradId", noradId,
                    "highRiskEventCount", events.size(),
                    "events", events
            ));
        } catch (Exception e) {
            log.error("Error fetching high-risk events for NORAD {}: ", noradId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "status", "error",
                            "message", "Failed to fetch high-risk events: " + e.getMessage()
                    ));
        }
    }

    @DeleteMapping("/cleanup")
    public  ResponseEntity<?> cleanupOldEvents(@RequestParam(defaultValue = "30") int daysToKeep) {
        try {
            log.info("Cleaning up events older than {} days", daysToKeep);
            conjunctionAnalysisService.cleanupOldEvents(daysToKeep);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Cleaned up events older than " + daysToKeep + " days"
            ));
        } catch (Exception e) {
            log.error("Error cleaning up events: ", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "status", "error",
                            "message", "Failed to clean up events: " + e.getMessage()
                    ));
        }
    }
}