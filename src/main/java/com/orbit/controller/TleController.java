package com.orbit.controller;

import com.orbit.service.SpaceTrackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tle")
@RequiredArgsConstructor
@Slf4j
public class TleController {
    private final SpaceTrackService spaceTrackService;

    @PostMapping("/fetch/all")
    public ResponseEntity<String> fetchAllTles() {
        try{
            log.info("Received request to fetch all TLE data");
            spaceTrackService.fetchAndSaveLatestTles();
            return ResponseEntity.ok().body("Successfully fetched and saved TLE data");
        } catch (Exception e){
            log.error("Error fetching TLE data: ", e);
            return ResponseEntity.internalServerError()
                    .body("Failed to fetch TLE data: " + e.getMessage());
        }
    }

    @PostMapping("/fetch/{noradId}")
    public ResponseEntity<String> fetchTleByNoradId(@PathVariable Integer noradId) {
        try{
            log.info("Received request to fetch TLE data by norad ID: {}", noradId);
            spaceTrackService.fetchAndSaveTleByNoradId(noradId);
            return ResponseEntity.ok().body("Successfully fetched and saved TLE data for NORAD ID: " + noradId);
        } catch (Exception e){
            log.error("Error fetching TLE data by norad ID {}: ", noradId, e);
            return ResponseEntity.internalServerError()
                    .body("Failed to fetch TLE data: " + e.getMessage());
        }
    }
}
