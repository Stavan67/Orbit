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

    @Value("${conjunction.filter.inclination.tolerance.deg:30.0}")
    private double inclinationToleranceDeg;

    private static final double CO_LOCATION_ALTITUDE_TOLERANCE_KM = 1.0;
    private static final double CO_LOCATION_INCLINATION_TOLERANCE_DEG = 0.1;
    private static final double CO_LOCATION_RAAN_TOLERANCE_DEG = 0.1;
    private static final double CO_LOCATION_MEAN_MOTION_TOLERANCE = 0.0001;

    public OrbitalElements extractOrbitalElements(TleData tleData) {
        Satellite sat = tleData.getSatellite();
        double meanMotionRad = tleData.getMeanMotion() * 2 * Math.PI /  86400.0;
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

    public List<TleData> filterCandidates(
            TleData primaryTle,
            List<TleData> allTles
    ){
        OrbitalElements primaryElements = extractOrbitalElements(primaryTle);
        if(!primaryElements.isLeo()){
            log.warn("Primary satellite {} is not in LEO (alt={} km)",
                    primaryElements.getNoradId(), primaryElements.getAltitude());
        }

        log.info("Filtering candidates for primary NORAD {} at altitude {} km, inclination {} deg",
                primaryElements.getNoradId(),
                String.format("%.1f", primaryElements.getAltitude()),
                String.format("%.1f", primaryElements.getInclination()));

        List<TleData> candidates = allTles.stream()
                .filter(tle -> {
                    if(tle.getSatellite().getNoradId().equals(primaryElements.getNoradId())){
                        return false;
                    }
                    OrbitalElements secondaryElements = extractOrbitalElements(tle);
                    if(!secondaryElements.isLeo()){
                        return false;
                    }
                    return primaryElements.canConjuctWith(
                            secondaryElements,
                            altitudeToleranceKm,
                            inclinationToleranceDeg
                    );
                })
                .collect(Collectors.toList());
        log.info("Coarse filter: {} satellites remain from {} total ({}% reduction)",
                candidates.size(),
                allTles.size(),
                String.format("%.1f", 100.0 * (1.0 - (double) candidates.size() / allTles.size())));
        return candidates;
    }

    public List<TleData> refineByRaan(
            TleData primaryTle,
            List<TleData> candidates,
            double raanToleranceDeg
    ) {
        OrbitalElements primaryElements = extractOrbitalElements(primaryTle);
        List<TleData> refined = candidates.stream()
                .filter(tle -> {
                    OrbitalElements secElements = extractOrbitalElements(tle);
                    double raanDiff = Math.abs(primaryElements.getRaan() - secElements.getRaan());
                    if(raanDiff > 180){
                        raanDiff = 360 - raanDiff;
                    }
                    return raanDiff <= raanToleranceDeg;
                })
                .collect(Collectors.toList());
        log.info("RAAN refinement: {} satellites remain from {} candidates",
                refined.size(), candidates.size());
        return refined;
    }

    public List<TleData> filterOutCoLocated(
            TleData primaryTle,
            List<TleData> candidates
    ) {
        OrbitalElements primaryElements = extractOrbitalElements(primaryTle);
        int coLocatedCount = 0;

        List<TleData> filtered = candidates.stream()
                .filter(tle -> {
                    OrbitalElements secElements = extractOrbitalElements(tle);
                    boolean isCoLocated = areCoLocated(primaryElements, secElements);
                    if (isCoLocated) {
                        log.debug("Filtering out co-located satellite {} (identical orbit to primary {})",
                                secElements.getNoradId(), primaryElements.getNoradId());
                    }
                    return !isCoLocated;
                })
                .collect(Collectors.toList());

        coLocatedCount = candidates.size() - filtered.size();
        if (coLocatedCount > 0) {
            log.info("Co-location filter: removed {} co-located objects (likely ISS modules or physically attached)",
                    coLocatedCount);
        }

        return filtered;
    }

    private boolean areCoLocated(OrbitalElements e1, OrbitalElements e2) {
        double altDiff = Math.abs(e1.getAltitude() - e2.getAltitude());
        if (altDiff > CO_LOCATION_ALTITUDE_TOLERANCE_KM) {
            return false;
        }

        double incDiff = Math.abs(e1.getInclination() - e2.getInclination());
        if (incDiff > CO_LOCATION_INCLINATION_TOLERANCE_DEG) {
            return false;
        }

        double raanDiff = Math.abs(e1.getRaan() - e2.getRaan());
        if (raanDiff > 180) {
            raanDiff = 360 - raanDiff;
        }
        if (raanDiff > CO_LOCATION_RAAN_TOLERANCE_DEG) {
            return false;
        }

        double meanMotionDiff = Math.abs(e1.getMeanMotion() - e2.getMeanMotion());
        if (meanMotionDiff > CO_LOCATION_MEAN_MOTION_TOLERANCE) {
            return false;
        }

        return true;
    }
}