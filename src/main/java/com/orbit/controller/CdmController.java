package com.orbit.controller;

import com.orbit.entity.CdmData;
import com.orbit.repository.CdmDataRepository;
import com.orbit.service.CdmIngestionService;
import com.orbit.service.SpaceTrackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cdm")
@RequiredArgsConstructor
@Slf4j
public class CdmController {

    private final CdmIngestionService cdmIngestionService;
    private final CdmDataRepository cdmDataRepository;
    private final SpaceTrackService spaceTrackService;
    @PostMapping("/fetch/{noradId}")
    public ResponseEntity<?> fetchCdms(@PathVariable Integer noradId) {
        try {
            log.info("Manual CDM fetch triggered for NORAD {}", noradId);
            String authCookie = spaceTrackService.getOrRefreshAuthCookie();
            List<CdmData> ingested = cdmIngestionService.fetchAndStoreCdmsForSatellite(
                    noradId, authCookie);

            long highPcCount = ingested.stream()
                    .filter(c -> c.getPc() != null && c.getPc() >= 1e-4)
                    .count();

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "noradId", noradId,
                    "ingestedCdms", ingested.size(),
                    "highPcCdms", highPcCount,
                    "message", String.format(
                            "Ingested %d new CDMs (%d with Pc ≥ 1e-4)", ingested.size(), highPcCount)
            ));
        } catch (Exception e) {
            log.error("CDM fetch failed for NORAD {}: {}", noradId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "CDM fetch failed: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/{noradId}")
    public ResponseEntity<?> getCdmsForSatellite(@PathVariable Integer noradId) {
        try {
            List<CdmData> cdms = cdmDataRepository.findByNoradId1AndTcaAfter(
                    noradId, LocalDateTime.now());

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "noradId", noradId,
                    "count", cdms.size(),
                    "cdms", cdms
            ));
        } catch (Exception e) {
            log.error("Failed to retrieve CDMs for NORAD {}: {}", noradId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to retrieve CDMs: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/{noradId}/high-pc")
    public ResponseEntity<?> getHighPcCdms(
            @PathVariable Integer noradId,
            @RequestParam(defaultValue = "1e-4") double minPc
    ) {
        try {
            List<CdmData> cdms = cdmDataRepository.findHighPcCdms(minPc, LocalDateTime.now())
                    .stream()
                    .filter(c -> c.getNoradId1().equals(noradId))
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "noradId", noradId,
                    "minPcThreshold", minPc,
                    "count", cdms.size(),
                    "cdms", cdms
            ));
        } catch (Exception e) {
            log.error("Failed to retrieve high-Pc CDMs for NORAD {}: {}", noradId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to retrieve high-Pc CDMs: " + e.getMessage()
            ));
        }
    }
}