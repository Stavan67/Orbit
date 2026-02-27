package com.orbit.service;

import com.orbit.dto.OrbitalElements;
import com.orbit.entity.Satellite;
import com.orbit.entity.TleData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SatelliteFilterService {

    @Value("${conjunction.filter.altitude.tolerance.km:150.0}")
    private double altitudeToleranceKm;

    /**
     * Inclination tolerance in degrees. Note: we compare *effective* inclinations
     * (i.e. min(i, 180-i), which represents the maximum latitude a satellite reaches).
     * Default raised to 60° to capture cross-inclination conjunctions that are common
     * in LEO (e.g. 97° SSO primary vs. 28°–75° debris).
     */
    @Value("${conjunction.filter.inclination.tolerance.deg:60.0}")
    private double inclinationToleranceDeg;

    /**
     * Inclination above which a satellite is considered "polar". Polar satellites
     * have ground tracks that cross every longitude and every RAAN, so RAAN-based
     * pre-filtering must be skipped for them entirely.
     */
    private static final double POLAR_INCLINATION_THRESHOLD_DEG = 60.0;

    private static final double CO_LOCATION_ALTITUDE_TOLERANCE_KM = 1.0;
    private static final double CO_LOCATION_INCLINATION_TOLERANCE_DEG = 0.1;
    private static final double CO_LOCATION_RAAN_TOLERANCE_DEG = 0.1;
    private static final double CO_LOCATION_MEAN_MOTION_TOLERANCE = 0.0001;

    public OrbitalElements extractOrbitalElements(TleData tleData) {
        Satellite sat = tleData.getSatellite();
        double meanMotionRad = tleData.getMeanMotion() * 2 * Math.PI / 86400.0;
        double mu = 398600.4418;
        double semiMajorAxis = Math.cbrt(mu / (meanMotionRad * meanMotionRad));
        double earthRadius = 6378.137;
        double altitude = semiMajorAxis - earthRadius;

        return new OrbitalElements(
                sat.getNoradId(),
                sat.getName(),
                semiMajorAxis,
                tleData.getEccentricity(),
                tleData.getInclination(),
                tleData.getRaan(),
                tleData.getMeanMotion(),
                altitude
        );
    }

    public List<TleData> filterCandidates(TleData primaryTle, List<TleData> allTles) {
        OrbitalElements primaryElements = extractOrbitalElements(primaryTle);
        if (!primaryElements.isLeo()) {
            log.warn("Primary satellite {} is not in LEO (alt={} km)",
                    primaryElements.getNoradId(), primaryElements.getAltitude());
        }

        log.info("Filtering candidates for primary NORAD {} | altitude {}-{} km (mean {}) | inclination {} deg",
                primaryElements.getNoradId(),
                String.format("%.1f", primaryElements.getPerigeeAltitude()),
                String.format("%.1f", primaryElements.getApogeeAltitude()),
                String.format("%.1f", primaryElements.getAltitude()),
                String.format("%.1f", primaryElements.getInclination()));

        List<TleData> candidates = allTles.stream()
                .filter(tle -> {
                    if (tle.getSatellite().getNoradId().equals(primaryElements.getNoradId())) {
                        return false;
                    }
                    OrbitalElements secondary = extractOrbitalElements(tle);

                    if (!secondary.passesThroughLeo()) return false;

                    return primaryElements.canConjuctWith(
                            secondary, altitudeToleranceKm, inclinationToleranceDeg);
                })
                .collect(Collectors.toList());

        log.info("Coarse filter: {} satellites remain from {} total ({}% reduction)",
                candidates.size(), allTles.size(),
                String.format("%.1f", 100.0 * (1.0 - (double) candidates.size() / allTles.size())));
        return candidates;
    }

    /**
     * Optionally narrows the candidate set using RAAN proximity.
     *
     * CRITICAL RULE: If the primary satellite is polar (inclination > 60°),
     * this filter MUST be skipped entirely. A polar satellite's ground track
     * crosses every meridian on Earth, meaning it can encounter objects at
     * ANY right ascension of the ascending node. Applying a RAAN window to
     * a polar primary will silently discard valid conjunctions — exactly the
     * bug that caused real Space-Track CDM pairs to be missed.
     *
     * For non-polar (low-inclination) primaries, a single RAAN window is a
     * reasonable heuristic because their ground tracks stay in a narrower
     * longitude band, making cross-RAAN conjunctions geometrically rare.
     */
    public List<TleData> refineByRaan(
            TleData primaryTle,
            List<TleData> candidates,
            double raanToleranceDeg
    ) {
        OrbitalElements primaryElements = extractOrbitalElements(primaryTle);
        boolean primaryIsPolar = primaryElements.getInclination() > POLAR_INCLINATION_THRESHOLD_DEG
                || primaryElements.getInclination() < (180.0 - POLAR_INCLINATION_THRESHOLD_DEG);

        // FIX: For polar/high-inclination primaries, skip RAAN filtering entirely.
        // Their ground track crosses every RAAN — no candidate can be ruled out
        // on RAAN grounds alone.
        if (primaryIsPolar) {
            log.info("Polar primary (i={} deg) — RAAN filter SKIPPED. "
                            + "Polar ground tracks cross all RAANs; filtering here would drop valid conjunctions.",
                    String.format("%.1f", primaryElements.getInclination()));
            return candidates;
        }

        // Non-polar case: single-window RAAN filter is a reasonable heuristic.
        List<TleData> refined = candidates.stream()
                .filter(tle -> {
                    OrbitalElements secondary = extractOrbitalElements(tle);
                    double raanDiff = Math.abs(primaryElements.getRaan() - secondary.getRaan());
                    if (raanDiff > 180.0) raanDiff = 360.0 - raanDiff;
                    return raanDiff <= raanToleranceDeg;
                })
                .collect(Collectors.toList());

        log.info("RAAN refinement (non-polar): {} satellites remain from {} candidates",
                refined.size(), candidates.size());
        return refined;
    }

    public List<TleData> filterOutCoLocated(TleData primaryTle, List<TleData> candidates) {
        OrbitalElements primaryElements = extractOrbitalElements(primaryTle);

        List<TleData> filtered = candidates.stream()
                .filter(tle -> {
                    OrbitalElements secondary = extractOrbitalElements(tle);
                    boolean isCoLocated = areCoLocated(primaryElements, secondary);
                    if (isCoLocated) {
                        log.debug("Filtering out co-located satellite {} (identical orbit to primary {})",
                                secondary.getNoradId(), primaryElements.getNoradId());
                    }
                    return !isCoLocated;
                })
                .collect(Collectors.toList());

        int removed = candidates.size() - filtered.size();
        if (removed > 0) {
            log.info("Co-location filter: removed {} co-located objects "
                    + "(likely attached modules or same launch cluster)", removed);
        }
        return filtered;
    }

    private boolean areCoLocated(OrbitalElements e1, OrbitalElements e2) {
        if (Math.abs(e1.getAltitude() - e2.getAltitude()) > CO_LOCATION_ALTITUDE_TOLERANCE_KM)
            return false;
        if (Math.abs(e1.getInclination() - e2.getInclination()) > CO_LOCATION_INCLINATION_TOLERANCE_DEG)
            return false;

        double raanDiff = Math.abs(e1.getRaan() - e2.getRaan());
        if (raanDiff > 180.0) raanDiff = 360.0 - raanDiff;
        if (raanDiff > CO_LOCATION_RAAN_TOLERANCE_DEG) return false;

        if (Math.abs(e1.getMeanMotion() - e2.getMeanMotion()) > CO_LOCATION_MEAN_MOTION_TOLERANCE)
            return false;

        return true;
    }
}