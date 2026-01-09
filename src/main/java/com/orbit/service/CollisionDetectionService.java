package com.orbit.service;

import com.orbit.entity.CollisionAlert;
import com.orbit.entity.Satellite;
import com.orbit.entity.TleData;
import com.orbit.repository.CollisionAlertRepository;
import com.orbit.repository.SatelliteRepository;
import com.orbit.repository.TleDataRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CollisionDetectionService {
    private final CoLocationFilterService coLocationFilter;
    private final SatelliteRepository satelliteRepository;
    private final TleDataRepository tleDataRepository;
    private final CollisionAlertRepository collisionAlertRepository;
    private final OrbitalPropagationService propagationService;
    @Value("${collision.detection.parallel-threads:8}")
    private int parallelThreads;
    @Value("${collision.detection.search-radius-km:100}")
    private double searchRadiusKm;
    private static final double CRITICAL_DISTANCE_KM = 1.0;
    private static final double HIGH_DISTANCE_KM = 5.0;
    private static final double MEDIUM_DISTANCE_KM = 10.0;
    private static final double LOW_DISTANCE_KM = 25.0;

    private record SpatialCell(int x, int y, int z) {
    }

    private record SatellitePosition(TleData tle, double[] position, boolean isHighPriority) {
    }

    @Transactional
    public int detectCollisions(int lookAheadHours) {
        log.info("Starting FULL collision detection for next {} hours", lookAheadHours);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime searchEnd = now.plusHours(lookAheadHours);

        List<TleData> allTles = tleDataRepository.findByIsCurrentTrue();
        log.info("Loaded {} satellites from database", allTles.size());

        if (allTles.isEmpty()) {
            log.warn("No satellites found in database");
            return 0;
        }

        log.info("Calculating current positions for all satellites...");
        List<SatellitePosition> satPositions = calculateAllPositions(allTles, now);

        log.info("Building spatial index...");
        Map<SpatialCell, List<SatellitePosition>> spatialGrid = buildSpatialGrid(satPositions);
        log.info("Created {} spatial cells", spatialGrid.size());

        log.info("Searching for close approaches...");
        int alertCount = detectCollisionsWithSpatialIndex(spatialGrid, now, searchEnd);

        log.info("Collision detection complete. Generated {} alerts", alertCount);
        return alertCount;
    }

    private List<SatellitePosition> calculateAllPositions(List<TleData> tles, LocalDateTime time) {
        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
        List<Future<SatellitePosition>> futures = new ArrayList<>();
        for (TleData tle : tles) {
            futures.add(executor.submit(() -> {
                try {
                    double[] position = propagationService.calculatePosition(tle, time);
                    boolean isHighPriority = isHighPrioritySatellite(tle);
                    return new SatellitePosition(tle, position, isHighPriority);
                } catch (Exception e) {
                    log.debug("Failed to calculate position for satellite {}", tle.getSatelliteId());
                    return null;
                }
            }));
        }
        List<SatellitePosition> positions = new ArrayList<>();
        int processed = 0;
        for (Future<SatellitePosition> future : futures) {
            try {
                SatellitePosition pos = future.get();
                if (pos != null) {
                    positions.add(pos);
                }
                processed++;
                if (processed % 5000 == 0) {
                    log.info("Calculated positions for {}/{} satellites", processed, tles.size());
                }
            } catch (Exception e) {
                log.debug("Error getting position result", e);
            }
        }
        executor.shutdown();
        log.info("Successfully calculated positions for {}/{} satellites", positions.size(), tles.size());
        return positions;
    }

    private Map<SpatialCell, List<SatellitePosition>> buildSpatialGrid(List<SatellitePosition> positions) {
        Map<SpatialCell, List<SatellitePosition>> grid = new HashMap<>();
        for (SatellitePosition satPos : positions) {
            int cellX = (int) Math.floor(satPos.position[0] / searchRadiusKm);
            int cellY = (int) Math.floor(satPos.position[1] / searchRadiusKm);
            int cellZ = (int) Math.floor(satPos.position[2] / searchRadiusKm);

            SpatialCell cell = new SpatialCell(cellX, cellY, cellZ);
            grid.computeIfAbsent(cell, k -> new ArrayList<>()).add(satPos);
        }
        return grid;
    }

    private int detectCollisionsWithSpatialIndex(Map<SpatialCell, List<SatellitePosition>> grid,
                                                 LocalDateTime now,
                                                 LocalDateTime searchEnd) {
        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
        List<Future<Integer>> futures = new ArrayList<>();
        List<SpatialCell> cells = new ArrayList<>(grid.keySet());
        int totalCells = cells.size();
        for (int i = 0; i < cells.size(); i++) {
            final int cellIndex = i;
            final SpatialCell cell = cells.get(i);
            futures.add(executor.submit(() -> {
                int alerts = 0;
                List<SatellitePosition> satellitesInCell = grid.get(cell);
                for (int j = 0; j < satellitesInCell.size(); j++) {
                    for (int k = j + 1; k < satellitesInCell.size(); k++) {
                        if (checkAndCreateAlert(satellitesInCell.get(j), satellitesInCell.get(k),
                                now, searchEnd)) {
                            alerts++;
                        }
                    }
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            SpatialCell neighbor = new SpatialCell(
                                    cell.x + dx, cell.y + dy, cell.z + dz
                            );
                            List<SatellitePosition> neighborsInCell = grid.get(neighbor);
                            if (neighborsInCell != null) {
                                for (SatellitePosition sat1 : satellitesInCell) {
                                    for (SatellitePosition sat2 : neighborsInCell) {
                                        // Avoid duplicate checks
                                        if (sat1.tle.getSatelliteId() < sat2.tle.getSatelliteId()) {
                                            if (checkAndCreateAlert(sat1, sat2, now, searchEnd)) {
                                                alerts++;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (cellIndex % 100 == 0) {
                    log.info("Processed {}/{} spatial cells", cellIndex, totalCells);
                }
                return alerts;
            }));
        }

        int totalAlerts = 0;
        for (Future<Integer> future : futures) {
            try {
                totalAlerts += future.get();
            } catch (Exception e) {
                log.error("Error processing spatial cell", e);
            }
        }
        executor.shutdown();
        return totalAlerts;
    }

    private boolean checkAndCreateAlert(SatellitePosition sat1, SatellitePosition sat2,
                                        LocalDateTime now, LocalDateTime searchEnd) {
        try {
            Optional<Satellite> satellite1 = satelliteRepository.findById(sat1.tle.getSatelliteId());
            Optional<Satellite> satellite2 = satelliteRepository.findById(sat2.tle.getSatelliteId());

            if (satellite1.isEmpty() || satellite2.isEmpty()) {
                return false;
            }

            if (coLocationFilter.shouldSkipPair(satellite1.get(), satellite2.get(), sat1.tle, sat2.tle)) {
                return false;
            }

            double currentDistance = propagationService.calculateEuclideanDistance(
                    sat1.position, sat2.position
            );

            if (currentDistance > searchRadiusKm) {
                return false;
            }

            int stepMinutes = (sat1.isHighPriority || sat2.isHighPriority) ? 10 : 30;

            double[] tcaResult = propagationService.findClosestApproach(
                    sat1.tle, sat2.tle, now, searchEnd, stepMinutes
            );

            double minDistance = tcaResult[1];
            if (minDistance < LOW_DISTANCE_KM) {
                LocalDateTime tca = LocalDateTime.ofEpochSecond(
                        (long) tcaResult[0], 0, ZoneOffset.UTC
                );
                CollisionAlert alert = createCollisionAlert(
                        sat1.tle, sat2.tle, tca, minDistance,
                        sat1.isHighPriority || sat2.isHighPriority
                );
                if (alert != null) {
                    String priority = (sat1.isHighPriority || sat2.isHighPriority) ? "HIGH-PRIORITY" : "ROUTINE";
                    log.warn("[{}] COLLISION ALERT: Sat {} <-> Sat {} | Distance: {} km | TCA: {}",
                            priority,
                            sat1.tle.getSatelliteId(),
                            sat2.tle.getSatelliteId(),
                            String.format("%.2f", minDistance),  // Format it here
                            tca);
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Error checking satellite pair", e);
        }
        return false;
    }

    private boolean isHighPrioritySatellite(TleData tle) {
        if (tle.getMeanMotion() == null || tle.getEccentricity() == null) {
            return false;
        }
        double meanMotion = tle.getMeanMotion();
        double eccentricity = tle.getEccentricity();
        return meanMotion > 11.0 && eccentricity < 0.05;
    }

    @Transactional
    public CollisionAlert checkSatellitePair(Long satellite1Id, Long satellite2Id,
                                             int lookAheadHours) {
        log.info("Checking collision risk between satellites {} and {}",
                satellite1Id, satellite2Id);

        Optional<TleData> tle1Opt = tleDataRepository.findCurrentTleBySatelliteId(satellite1Id);
        Optional<TleData> tle2Opt = tleDataRepository.findCurrentTleBySatelliteId(satellite2Id);

        if (tle1Opt.isEmpty() || tle2Opt.isEmpty()) {
            log.warn("TLE data not found for one or both satellites");
            return null;
        }

        TleData tle1 = tle1Opt.get();
        TleData tle2 = tle2Opt.get();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime searchEnd = now.plusHours(lookAheadHours);

        double[] tcaResult = propagationService.findClosestApproach(
                tle1, tle2, now, searchEnd, 10
        );

        double minDistance = tcaResult[1];
        LocalDateTime tca = LocalDateTime.ofEpochSecond(
                (long) tcaResult[0], 0, ZoneOffset.UTC
        );

        if (minDistance < LOW_DISTANCE_KM) {
            boolean isHighPriority = isHighPrioritySatellite(tle1) || isHighPrioritySatellite(tle2);
            return createCollisionAlert(tle1, tle2, tca, minDistance, isHighPriority);
        }

        log.info("No collision risk detected. Minimum distance: {} km",
                String.format("%.2f", minDistance));
        return null;
    }

    public double getCurrentDistance(Long satellite1Id, Long satellite2Id) {
        Optional<TleData> tle1Opt = tleDataRepository.findCurrentTleBySatelliteId(satellite1Id);
        Optional<TleData> tle2Opt = tleDataRepository.findCurrentTleBySatelliteId(satellite2Id);

        if (tle1Opt.isEmpty() || tle2Opt.isEmpty()) {
            throw new IllegalArgumentException("TLE data not found");
        }

        return propagationService.calculateDistance(
                tle1Opt.get(), tle2Opt.get(), LocalDateTime.now()
        );
    }

    private CollisionAlert createCollisionAlert(TleData tle1, TleData tle2,
                                                LocalDateTime tca, double minDistance,
                                                boolean isHighPriority) {
        try {
            Optional<CollisionAlert> existingAlert = collisionAlertRepository
                    .findBySatellitePairAndTca(tle1.getSatelliteId(), tle2.getSatelliteId(), tca);

            if (existingAlert.isPresent()) {
                return existingAlert.get();
            }

            double[] pv1 = propagationService.calculatePositionAndVelocity(tle1, tca);
            double[] pv2 = propagationService.calculatePositionAndVelocity(tle2, tca);
            double relativeVelocity = propagationService.calculateRelativeVelocity(pv1, pv2);

            CollisionAlert alert = new CollisionAlert();
            alert.setSatellite1Id(tle1.getSatelliteId());
            alert.setSatellite2Id(tle2.getSatelliteId());
            alert.setTca(tca);
            alert.setMinDistance(minDistance);
            alert.setRelativeVelocity(relativeVelocity);

            String baseLevel = determineAlertLevel(minDistance);
            if (isHighPriority && !baseLevel.equals("CRITICAL")) {
                baseLevel = boostAlertLevel(baseLevel);
            }
            alert.setAlertLevel(baseLevel);

            alert.setCollisionProbability(estimateCollisionProbability(minDistance, relativeVelocity));

            alert.setSatellite1PositionX(pv1[0]);
            alert.setSatellite1PositionY(pv1[1]);
            alert.setSatellite1PositionZ(pv1[2]);
            alert.setSatellite2PositionX(pv2[0]);
            alert.setSatellite2PositionY(pv2[1]);
            alert.setSatellite2PositionZ(pv2[2]);

            return collisionAlertRepository.save(alert);
        } catch (Exception e) {
            log.error("Error creating collision alert", e);
            return null;
        }
    }

    private String determineAlertLevel(double distance) {
        if (distance < CRITICAL_DISTANCE_KM) return "CRITICAL";
        else if (distance < HIGH_DISTANCE_KM) return "HIGH";
        else if (distance < MEDIUM_DISTANCE_KM) return "MEDIUM";
        else return "LOW";
    }

    private String boostAlertLevel(String level) {
        return switch (level) {
            case "LOW" -> "MEDIUM";
            case "MEDIUM" -> "HIGH";
            case "HIGH" -> "CRITICAL";
            default -> level;
        };
    }

    private Double estimateCollisionProbability(double distance, double relativeVelocity) {
        double hardBodyRadius = 0.01;
        double uncertaintyRadius = 0.01 + (0.01 * relativeVelocity);
        double combinedRadius = hardBodyRadius + uncertaintyRadius;

        if (distance < combinedRadius) {
            return 0.99;
        }

        if (distance < 3 * combinedRadius) {
            return 0.50;
        }

        double velocityFactor = 1.0 + (relativeVelocity / 15.0);
        double probability = velocityFactor * Math.exp(-distance / combinedRadius);

        return Math.min(probability, 0.99);
    }

    public List<CollisionAlert> getActiveAlerts() {
        return collisionAlertRepository.findByIsResolvedFalse();
    }

    public List<CollisionAlert> getAlertsByLevel(String level) {
        return collisionAlertRepository.findByAlertLevelAndIsResolvedFalse(level);
    }

    @Transactional
    public void resolveAlert(Long alertId) {
        collisionAlertRepository.findById(alertId).ifPresent(alert -> {
            alert.setIsResolved(true);
            collisionAlertRepository.save(alert);
            log.info("Resolved collision alert {}", alertId);
        });
    }
}