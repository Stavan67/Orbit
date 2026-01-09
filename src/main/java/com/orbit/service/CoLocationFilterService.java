package com.orbit.service;

import com.orbit.entity.Satellite;
import com.orbit.entity.TleData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
public class CoLocationFilterService {
    private static final List<Integer> SPACE_STATION_NORAD_IDS = Arrays.asList(
            25544,  // ISS (Zarya)
            15902,  // ISS (Nauka)
            48274,  // CSS Tianhe-1
            17388,  // CSS Wentian
            18086   // CSS Mengtian
    );
    public boolean shouldSkipPair(Satellite sat1, Satellite sat2, TleData tle1, TleData tle2) {
        if (isSpaceStationRelated(sat1) && isSpaceStationRelated(sat2)) {
            log.debug("Skipping space station pair: {} <-> {}", sat1.getName(), sat2.getName());
            return true;
        }
        if (isRecentlyDeployedConstellation(sat1, sat2, tle1, tle2)) {
            log.debug("Skipping recently deployed constellation: {} <-> {}", sat1.getName(), sat2.getName());
            return true;
        }
        if (hasIdenticalOrbits(tle1, tle2)) {
            log.debug("Skipping co-located satellites: {} <-> {}", sat1.getName(), sat2.getName());
            return true;
        }
        return false;
    }

    private boolean isSpaceStationRelated(Satellite satellite) {
        if (SPACE_STATION_NORAD_IDS.contains(satellite.getNoradId())) {
            return true;
        }
        String name = satellite.getName().toUpperCase();
        return name.contains("ISS") ||
                name.contains("CSS") ||
                name.contains("TIANHE") ||
                name.contains("WENTIAN") ||
                name.contains("MENGTIAN") ||
                name.contains("NAUKA") ||
                name.contains("PROGRESS") ||
                name.contains("SOYUZ") ||
                name.contains("DRAGON") ||
                name.contains("CYGNUS") ||
                name.contains("HTV") ||
                name.contains("TIANZHOU") ||
                name.contains("SZ-"); // Shenzhou
    }

    private boolean isRecentlyDeployedConstellation(Satellite sat1, Satellite sat2,
                                                    TleData tle1, TleData tle2) {
        if (!hasSameConstellationName(sat1.getName(), sat2.getName())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        long daysSinceEpoch = ChronoUnit.DAYS.between(tle1.getEpoch(), now);
        if (daysSinceEpoch > 7) {
            return false;
        }
        return hasNearIdenticalOrbits(tle1, tle2);
    }

    private boolean hasSameConstellationName(String name1, String name2) {
        String[] constellations = {"STARLINK", "ONEWEB", "IRIDIUM", "GLOBALSTAR", "PLANET", "FLOCK"};

        for (String constellation : constellations) {
            if (name1.toUpperCase().contains(constellation) &&
                    name2.toUpperCase().contains(constellation)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasIdenticalOrbits(TleData tle1, TleData tle2) {
        if (tle1.getMeanMotion() == null || tle2.getMeanMotion() == null) {
            return false;
        }
        double meanMotionTolerance = 0.0001;    // revs/day
        double inclinationTolerance = 0.01;     // degrees
        double eccentricityTolerance = 0.0001;
        double raanTolerance = 0.1;             // degrees
        double argOfPerigeeTolerance = 0.1;     // degrees

        boolean identicalMeanMotion = Math.abs(tle1.getMeanMotion() - tle2.getMeanMotion()) < meanMotionTolerance;
        boolean identicalInclination = Math.abs(tle1.getInclination() - tle2.getInclination()) < inclinationTolerance;
        boolean identicalEccentricity = Math.abs(tle1.getEccentricity() - tle2.getEccentricity()) < eccentricityTolerance;
        boolean identicalRaan = Math.abs(tle1.getRaan() - tle2.getRaan()) < raanTolerance;
        boolean identicalArgOfPerigee = Math.abs(tle1.getArgumentOfPerigee() - tle2.getArgumentOfPerigee()) < argOfPerigeeTolerance;

        return identicalMeanMotion && identicalInclination && identicalEccentricity &&
                identicalRaan && identicalArgOfPerigee;
    }

    private boolean hasNearIdenticalOrbits(TleData tle1, TleData tle2) {
        if (tle1.getMeanMotion() == null || tle2.getMeanMotion() == null) {
            return false;
        }
        double meanMotionTolerance = 0.001;     // revs/day
        double inclinationTolerance = 0.1;      // degrees
        double raanTolerance = 1.0;             // degrees

        boolean nearMeanMotion = Math.abs(tle1.getMeanMotion() - tle2.getMeanMotion()) < meanMotionTolerance;
        boolean nearInclination = Math.abs(tle1.getInclination() - tle2.getInclination()) < inclinationTolerance;
        boolean nearRaan = Math.abs(tle1.getRaan() - tle2.getRaan()) < raanTolerance;

        return nearMeanMotion && nearInclination && nearRaan;
    }

    public String getSkipReason(Satellite sat1, Satellite sat2, TleData tle1, TleData tle2) {
        if (isSpaceStationRelated(sat1) && isSpaceStationRelated(sat2)) {
            return "Space station modules or docked spacecraft";
        }
        if (isRecentlyDeployedConstellation(sat1, sat2, tle1, tle2)) {
            long daysSinceEpoch = ChronoUnit.DAYS.between(tle1.getEpoch(), LocalDateTime.now());
            return String.format("Recently deployed constellation (launched %d days ago)", daysSinceEpoch);
        }
        if (hasIdenticalOrbits(tle1, tle2)) {
            return "Intentionally co-located satellites (same orbital slot)";
        }
        return "Unknown";
    }
}