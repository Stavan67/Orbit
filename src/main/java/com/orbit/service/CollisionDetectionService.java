package com.orbit.service;

import com.orbit.entity.CollisionAlert;
import com.orbit.entity.TleData;
import com.orbit.repository.CollisionAlertRepository;
import com.orbit.repository.TleDataRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CollisionDetectionService {
    private final TleDataRepository tleDataRepository;
    private final CollisionAlertRepository collisionAlertRepository;
    private final OrbitalPropagationService propagationService;
    private static final double CRITICAL_DISTANCE_KM = 1.0;
    private static final double HIGH_DISTANCE_KM = 5.0;
    private static final double MEDIUM_DISTANCE_KM = 10.0;
    private static final double LOW_DISTANCE_KM = 25.0;

    @Transactional
    public int detectCollisions(int lookAheadHours) {
        log.info("Starting collision detection for next {} hours", lookAheadHours);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime searchEnd = now.plusHours(lookAheadHours);

        List<TleData> currentTles = tleDataRepository.findByIsCurrentTrue();
        log.info("Analyzing {} satellites for potential collisions", currentTles.size());

        int alertCount = 0;
        int comparisons = 0;

        for (int i = 0; i < currentTles.size(); i++) {
            TleData tle1 = currentTles.get(i);

            for (int j = i + 1; j < currentTles.size(); j++) {
                TleData tle2 = currentTles.get(j);
                comparisons++;

                try {
                    double[] tcaResult = propagationService.findClosestApproach(
                            tle1, tle2, now, searchEnd, 15
                    );

                    double minDistance = tcaResult[1];
                    LocalDateTime tca = LocalDateTime.ofEpochSecond(
                            (long) tcaResult[0], 0, ZoneOffset.UTC
                    );
                    if (minDistance < LOW_DISTANCE_KM) {
                        CollisionAlert alert = createCollisionAlert(
                                tle1, tle2, tca, minDistance
                        );
                        if (alert != null) {
                            alertCount++;
                            String distance = String.format("%.2f", minDistance);
                            log.warn("COLLISION ALERT: {} <-> {} | Distance: {} km | TCA: {}",
                                    tle1.getSatellite().getName(),
                                    tle2.getSatellite().getName(),
                                    minDistance,
                                    tca);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error comparing satellites {} and {}",
                            tle1.getSatelliteId(), tle2.getSatelliteId(), e);
                }
                if (comparisons % 10000 == 0) {
                    log.info("Processed {}/{} satellite pairs", comparisons,
                            (currentTles.size() * (currentTles.size() - 1)) / 2);
                }
            }
        }
        log.info("Collision detection complete. Generated {} alerts from {} comparisons",
                alertCount, comparisons);
        return alertCount;
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
                tle1, tle2, now, searchEnd, 15
        );

        double minDistance = tcaResult[1];
        LocalDateTime tca = LocalDateTime.ofEpochSecond(
                (long) tcaResult[0], 0, ZoneOffset.UTC
        );

        if (minDistance < LOW_DISTANCE_KM) {
            return createCollisionAlert(tle1, tle2, tca, minDistance);
        }

        log.info("No collision risk detected. Minimum distance: {} km",
                String.format("%.2f", minDistance));
        return null;
    }

    public double getCurrentDistance(Long satellite1Id, Long satellite2Id) {
        Optional<TleData> tle1Opt = tleDataRepository.findCurrentTleBySatelliteId(satellite1Id);
        Optional<TleData> tle2Opt = tleDataRepository.findCurrentTleBySatelliteId(satellite2Id);

        if (tle1Opt.isEmpty() || tle2Opt.isEmpty()) {
            throw new IllegalArgumentException("TLE data not found for one or both satellites");
        }

        return propagationService.calculateDistance(
                tle1Opt.get(),
                tle2Opt.get(),
                LocalDateTime.now()
        );
    }

    private CollisionAlert createCollisionAlert(TleData tle1, TleData tle2,
                                                LocalDateTime tca, double minDistance) {
        try {
            Optional<CollisionAlert> existingAlert = collisionAlertRepository
                    .findBySatellitePairAndTca(tle1.getSatelliteId(), tle2.getSatelliteId(), tca);

            if (existingAlert.isPresent()) {
                log.debug("Alert already exists for this satellite pair and TCA");
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
            alert.setAlertLevel(determineAlertLevel(minDistance));
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
        if (distance < CRITICAL_DISTANCE_KM) {
            return "CRITICAL";
        } else if (distance < HIGH_DISTANCE_KM) {
            return "HIGH";
        } else if (distance < MEDIUM_DISTANCE_KM) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }

    private Double estimateCollisionProbability(double distance, double relativeVelocity) {
        double combinedRadius = 0.02;
        if (distance < combinedRadius) {
            return 0.99;
        }
        double probability = Math.exp(-distance / combinedRadius);
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
        Optional<CollisionAlert> alertOpt = collisionAlertRepository.findById(alertId);
        if (alertOpt.isPresent()) {
            CollisionAlert alert = alertOpt.get();
            alert.setIsResolved(true);
            collisionAlertRepository.save(alert);
            log.info("Resolved collision alert {}", alertId);
        }
    }
}