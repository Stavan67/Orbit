package com.orbit.service;

import com.orbit.dto.ConjunctionResult;
import com.orbit.entity.TleData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConjunctionScreeningService {
    private final PropagationService propagationService;

    @Value("${conjunction.prediction.days:7}")
    private int predictionDays;

    @Value("${conjunction.time.step.seconds:30}")
    private int coarseTimeStepSeconds;

    @Value("${conjunction.min.distance.km:50.0}")
    private double minApproachDistanceKm;

    @Value("${conjunction.refinement.fine.step.seconds:1}")
    private int fineTimeStepSeconds;

    @Value("${conjunction.refinement.polish.step.seconds:0.1}")
    private double polishTimeStepSeconds;

    @Value("${conjunction.refinement.threshold.km:100.0}")
    private double refinementThresholdKm;

    private static final int REFINEMENT_WINDOW_SECONDS = 120;
    private static final int POLISH_WINDOW_SECONDS = 10;
    private static final double EARTH_RADIUS_KM = 6378.137;

    public ConjunctionResult screenPair(
            TLE primaryTLE,
            TLE secondaryTLE,
            Integer primaryNoradId,
            Integer secondaryNoradId,
            LocalDateTime screeningEpoch
    ) {
        try {
            TLEPropagator primaryProp = propagationService.createPropagator(primaryTLE);
            TLEPropagator secondaryProp = propagationService.createPropagator(secondaryTLE);

            AbsoluteDate startDate = propagationService.toAbsoluteDate(screeningEpoch);
            AbsoluteDate endDate = startDate.shiftedBy(predictionDays * 86400.0);

            CoarseResult coarseResult = coarseScan(
                    primaryProp,
                    secondaryProp,
                    startDate,
                    endDate
            );

            if (coarseResult == null || coarseResult.minDistance > minApproachDistanceKm * 1000) {
                return null;
            }

            FineResult fineResult = fineRefinement(
                    primaryProp,
                    secondaryProp,
                    coarseResult.tcaDate,
                    REFINEMENT_WINDOW_SECONDS,
                    fineTimeStepSeconds
            );

            if (fineResult.primaryAtTCA == null || fineResult.secondaryAtTCA == null) {
                log.warn("Fine refinement returned null PV for pair {}-{}; skipping.",
                        primaryNoradId, secondaryNoradId);
                return null;
            }

            FineResult polishedResult;
            if (fineResult.minDistance < refinementThresholdKm * 1000) {
                polishedResult = fineRefinement(
                        primaryProp,
                        secondaryProp,
                        fineResult.tcaDate,
                        POLISH_WINDOW_SECONDS,
                        polishTimeStepSeconds
                );
                if (polishedResult.primaryAtTCA == null || polishedResult.secondaryAtTCA == null) {
                    log.warn("Polish refinement returned null PV for pair {}-{}; using fine result.",
                            primaryNoradId, secondaryNoradId);
                    polishedResult = fineResult;
                }
            } else {
                polishedResult = fineResult;
            }

            double relativeVelocity = propagationService.calculateRelativeVelocity(
                    polishedResult.primaryAtTCA.getVelocity(),
                    polishedResult.secondaryAtTCA.getVelocity()
            );

            if (relativeVelocity < 0.5) {
                log.debug("Filtering out near-zero relative velocity ({} m/s) between {} and {} - likely co-located",
                        relativeVelocity, primaryNoradId, secondaryNoradId);
                return null;
            }

            double primaryAlt = (polishedResult.primaryAtTCA.getPosition().getNorm() / 1000.0) - EARTH_RADIUS_KM;
            double secondaryAlt = (polishedResult.secondaryAtTCA.getPosition().getNorm() / 1000.0) - EARTH_RADIUS_KM;

            LocalDateTime tca = propagationService.toLocalDateTime(polishedResult.tcaDate);

            ConjunctionResult result = new ConjunctionResult(
                    primaryNoradId,
                    secondaryNoradId,
                    tca,
                    polishedResult.minDistance,
                    relativeVelocity,
                    primaryAlt,
                    secondaryAlt
            );

            log.debug("Conjunction detected: Primary={}, Secondary={}, TCA={}, Miss={}m, RelVel={}m/s",
                    result.getPrimaryNoradId(),
                    result.getSecondaryNoradId(),
                    result.getTca(),
                    result.getMissDistance(),
                    result.getRelativeVelocity());

            return result;

        } catch (Exception e) {
            log.error("Error screening pair {}-{}: {}",
                    primaryNoradId,
                    secondaryNoradId,
                    e.getMessage());
            return null;
        }
    }

    private CoarseResult coarseScan(
            TLEPropagator primaryProp,
            TLEPropagator secondaryProp,
            AbsoluteDate startDate,
            AbsoluteDate endDate
    ) {
        double minDistance = Double.MAX_VALUE;
        AbsoluteDate tcaDate = null;
        PVCoordinates primaryAtTCA = null;
        PVCoordinates secondaryAtTCA = null;

        AbsoluteDate currentDate = startDate;
        int stepCount = 0;

        while (currentDate.compareTo(endDate) <= 0) {
            try {
                PVCoordinates primaryPV = propagationService.propagateToPV(primaryProp, currentDate);
                PVCoordinates secondaryPV = propagationService.propagateToPV(secondaryProp, currentDate);

                double distance = propagationService.calculateDistance(
                        primaryPV.getPosition(),
                        secondaryPV.getPosition()
                );

                if (distance < minDistance) {
                    minDistance = distance;
                    tcaDate = currentDate;
                    primaryAtTCA = primaryPV;
                    secondaryAtTCA = secondaryPV;
                }

                stepCount++;
            } catch (Exception e) {
                log.trace("Propagation failed at step {}: {}", stepCount, e.getMessage());
            }

            currentDate = currentDate.shiftedBy(coarseTimeStepSeconds);
        }

        if (tcaDate == null) {
            return null;
        }

        log.trace("Coarse scan: min distance = {}m at {} ({} steps)",
                minDistance, tcaDate, stepCount);

        return new CoarseResult(minDistance, tcaDate, primaryAtTCA, secondaryAtTCA);
    }

    private FineResult fineRefinement(
            TLEPropagator primaryProp,
            TLEPropagator secondaryProp,
            AbsoluteDate approximateTCA,
            int windowSeconds,
            double timeStepSeconds
    ) {
        AbsoluteDate startDate = approximateTCA.shiftedBy(-windowSeconds);
        AbsoluteDate endDate = approximateTCA.shiftedBy(windowSeconds);

        double minDistance = Double.MAX_VALUE;
        AbsoluteDate refinedTCA = approximateTCA;
        PVCoordinates primaryAtTCA = null;
        PVCoordinates secondaryAtTCA = null;

        AbsoluteDate currentDate = startDate;
        int stepCount = 0;

        while (currentDate.compareTo(endDate) <= 0) {
            try {
                PVCoordinates primaryPV = propagationService.propagateToPV(primaryProp, currentDate);
                PVCoordinates secondaryPV = propagationService.propagateToPV(secondaryProp, currentDate);

                double distance = propagationService.calculateDistance(
                        primaryPV.getPosition(),
                        secondaryPV.getPosition()
                );

                if (distance < minDistance) {
                    minDistance = distance;
                    refinedTCA = currentDate;
                    primaryAtTCA = primaryPV;
                    secondaryAtTCA = secondaryPV;
                }

                stepCount++;
            } catch (Exception e) {
                log.trace("Refinement propagation failed at step {}: {}", stepCount, e.getMessage());
            }

            currentDate = currentDate.shiftedBy(timeStepSeconds);
        }

        log.trace("Fine refinement (step={}s): min distance = {}m at {} ({} steps)",
                timeStepSeconds, minDistance, refinedTCA, stepCount);

        return new FineResult(refinedTCA, minDistance, primaryAtTCA, secondaryAtTCA);
    }

    public List<ConjunctionResult> screenMultiplePairs(
            TleData primaryTle,
            List<TleData> candidateTles,
            LocalDateTime screeningEpoch
    ) {
        List<ConjunctionResult> results = new ArrayList<>();

        log.info("Screening primary {} against {} candidates",
                primaryTle.getSatellite().getNoradId(),
                candidateTles.size());

        TLE primaryTLE = propagationService.createTLE(primaryTle);
        Integer primaryNoradId = primaryTle.getSatellite().getNoradId();

        Map<Integer, TLE> secondaryTLECache = new HashMap<>();
        int failedTLEs = 0;
        int staleTLEs = 0;

        for (TleData secondaryTle : candidateTles) {
            if (secondaryTle.getEpoch() != null) {
                long ageDays = ChronoUnit.DAYS.between(
                        secondaryTle.getEpoch(), LocalDateTime.now());
                if (ageDays > propagationService.getMaxTleAgeDays()) {
                    staleTLEs++;
                    log.debug("Skipping stale TLE for NORAD {} ({} days old)",
                            secondaryTle.getSatellite().getNoradId(), ageDays);
                    continue;
                }
            }
            try {
                TLE secondaryTLE = propagationService.createTLE(secondaryTle);
                secondaryTLECache.put(secondaryTle.getSatellite().getNoradId(), secondaryTLE);
            } catch (Exception e) {
                failedTLEs++;
                log.warn("Failed to create TLE for NORAD {}: {}",
                        secondaryTle.getSatellite().getNoradId(), e.getMessage());
            }
        }

        if (staleTLEs > 0) {
            log.warn("Skipped {} candidates with stale TLEs (> {} days old)",
                    staleTLEs, propagationService.getMaxTleAgeDays());
        }
        log.info("Successfully created {} TLE propagators ({} failed)",
                secondaryTLECache.size(), failedTLEs);

        java.util.Set<Integer> screenedIds = new java.util.HashSet<>();

        long startTime = System.currentTimeMillis();
        int processed = 0;

        for (TleData secondaryTle : candidateTles) {
            Integer secondaryNoradId = secondaryTle.getSatellite().getNoradId();

            if (!screenedIds.add(secondaryNoradId)) {
                log.debug("Skipping duplicate secondary NORAD {} in candidate list", secondaryNoradId);
                continue;
            }

            TLE secondaryTLE = secondaryTLECache.get(secondaryNoradId);

            if (secondaryTLE == null) {
                continue;
            }

            ConjunctionResult result = screenPair(
                    primaryTLE,
                    secondaryTLE,
                    primaryNoradId,
                    secondaryNoradId,
                    screeningEpoch
            );

            if (result != null) {
                results.add(result);
                log.debug("Found conjunction with NORAD {}: miss={}m, relVel={}m/s",
                        secondaryNoradId,
                        result.getMissDistance(),
                        result.getRelativeVelocity());
            }

            processed++;
            if (processed % 100 == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                double rate = processed / (elapsed / 1000.0);
                double percentComplete = 100.0 * processed / candidateTles.size();
                log.info("Progress: {}/{} pairs screened ({}%) - {} pairs/sec - {} conjunctions found",
                        processed,
                        candidateTles.size(),
                        percentComplete,
                        rate,
                        results.size());
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("Screening complete: {} conjunctions found from {} candidates in {} seconds ({} pairs/sec)",
                results.size(),
                candidateTles.size(),
                TimeUnit.MILLISECONDS.toSeconds(totalTime),
                candidateTles.size() / (totalTime / 1000.0));

        return results;
    }

    private record CoarseResult(
            double minDistance,
            AbsoluteDate tcaDate,
            PVCoordinates primaryAtTCA,
            PVCoordinates secondaryAtTCA
    ) {}

    private record FineResult(
            AbsoluteDate tcaDate,
            double minDistance,
            PVCoordinates primaryAtTCA,
            PVCoordinates secondaryAtTCA
    ) {}
}