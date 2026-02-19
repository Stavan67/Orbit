package com.orbit.service;

import com.orbit.dto.ConjunctionResult;
import com.orbit.entity.ConjunctionEvent;
import com.orbit.entity.Satellite;
import com.orbit.entity.TleData;
import com.orbit.repository.ConjunctionEventRepository;
import com.orbit.repository.SatelliteRepository;
import com.orbit.repository.TleDataRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConjunctionAnalysisService {
    private final SatelliteRepository satelliteRepository;
    private final TleDataRepository tleDataRepository;
    private final ConjunctionEventRepository conjunctionEventRepository;
    private final SatelliteFilterService filterService;
    private final ConjunctionScreeningService screeningService;
    private final RiskAssessmentService riskAssessmentService;
    private final PropagationService propagationService;

    @Value("${conjunction.filter.raan.tolerance.deg:45.0}")
    private double raanToleranceDeg;

    @Value("${conjunction.filter.raan.enabled:true}")
    private boolean useRaanFilter;

    @Transactional
    public List<ConjunctionEvent> analyzeConjunctions(Integer primaryNoradId) {
        log.info("Starting conjunction analysis for primary NORAD ID: {}", primaryNoradId);

        Optional<Satellite> primarySatOpt = satelliteRepository.findByNoradId(primaryNoradId);
        if(primarySatOpt.isEmpty()){
            throw new IllegalArgumentException("Primary satellite not found: " + primaryNoradId);
        }

        Satellite primarySat = primarySatOpt.get();
        Optional<TleData> primaryTleOpt = tleDataRepository.findBySatellite(primarySat);
        if(primaryTleOpt.isEmpty()){
            throw new IllegalArgumentException("No TLE data for satellite: " + primaryNoradId);
        }

        TleData primaryTle = primaryTleOpt.get();
        LocalDateTime screeningEpoch = LocalDateTime.now();

        if (primaryTle.getEpoch() != null) {
            long ageDays = ChronoUnit.DAYS.between(
                    primaryTle.getEpoch(), screeningEpoch);
            if (ageDays > propagationService.getMaxTleAgeDays()) {
                log.warn("Primary satellite {} TLE is {} days old (epoch {}). "
                                + "Conjunction results may be unreliable. "
                                + "Consider refreshing TLE data before re-running analysis.",
                        primaryNoradId, ageDays, primaryTle.getEpoch());
            }
        }

        log.info("Fetching all TLE data for filtering...");
        List<TleData> allTles = tleDataRepository.findAll();
        log.info("Total satellites in database: {}", allTles.size());

        List<TleData> candidates = filterService.filterCandidates(primaryTle, allTles);

        if(candidates.isEmpty()){
            log.info("No conjunction candidates found after altitude/inclination filtering");
            return new ArrayList<>();
        }

        log.info("After altitude/inclination filter: {} candidates remain", candidates.size());

        if (useRaanFilter && candidates.size() > 100) {
            log.info("Applying RAAN filter with tolerance {} degrees...", raanToleranceDeg);
            List<TleData> raanFiltered = filterService.refineByRaan(
                    primaryTle,
                    candidates,
                    raanToleranceDeg
            );

            double reductionPercent = 100.0 * (1.0 - (double) raanFiltered.size() / candidates.size());
            log.info("RAAN filter: {} satellites remain from {} ({} % reduction)",
                    raanFiltered.size(),
                    candidates.size(),
                    String.format("%.1f", reductionPercent));

            candidates = raanFiltered;
        } else if (useRaanFilter) {
            log.info("Skipping RAAN filter - only {} candidates (threshold: 100)", candidates.size());
        }

        if(candidates.isEmpty()){
            log.info("No conjunction candidates found after RAAN filtering");
            return new ArrayList<>();
        }

        log.info("Filtering out co-located satellites (ISS modules, physically attached objects)...");
        candidates = filterService.filterOutCoLocated(primaryTle, candidates);

        if(candidates.isEmpty()){
            log.info("No conjunction candidates found after co-location filtering");
            return new ArrayList<>();
        }

        log.info("Beginning detailed conjunction screening for {} candidates...", candidates.size());
        List<ConjunctionResult> conjunctionResults = screeningService.screenMultiplePairs(
                primaryTle,
                candidates,
                screeningEpoch
        );

        if(conjunctionResults.isEmpty()){
            log.info("No conjunctions detected within screening parameters");
            return new ArrayList<>();
        }

        List<ConjunctionEvent> events = new ArrayList<>();
        int secondaryNotFoundCount = 0;

        for(ConjunctionResult result : conjunctionResults){
            ConjunctionEvent.RiskLevel riskLevel = riskAssessmentService.assessRisk(result, screeningEpoch);
            Optional<Satellite> secondarySatOpt = satelliteRepository.findByNoradId(result.getSecondaryNoradId());

            if(secondarySatOpt.isEmpty()){
                log.warn("Secondary satellite not found: {}", result.getSecondaryNoradId());
                secondaryNotFoundCount++;
                continue;
            }

            ConjunctionEvent event = new ConjunctionEvent();
            event.setPrimarySatellite(primarySat);
            event.setSecondarySatellite(secondarySatOpt.get());
            event.setTca(result.getTca());
            event.setMissDistance(result.getMissDistance());
            event.setRelativeVelocity(result.getRelativeVelocity());
            event.setRiskLevel(riskLevel);
            event.setPrimaryAltitude(result.getPrimaryAltitude());
            event.setSecondaryAltitude(result.getSecondaryAltitude());
            event.setScreeningEpoch(screeningEpoch);
            events.add(event);

            if(riskAssessmentService.requiresAttention(riskLevel)){
                log.warn(riskAssessmentService.generateRiskSummary(result, riskLevel));
            }
        }

        if (secondaryNotFoundCount > 0) {
            log.warn("{} secondary satellites not found in database", secondaryNotFoundCount);
        }

        List<ConjunctionEvent> savedEvents = conjunctionEventRepository.saveAll(events);
        log.info("Saved {} conjunction events to database", savedEvents.size());

        long criticalCount = savedEvents.stream()
                .filter(e -> e.getRiskLevel() == ConjunctionEvent.RiskLevel.CRITICAL)
                .count();
        long highCount = savedEvents.stream()
                .filter(e -> e.getRiskLevel() == ConjunctionEvent.RiskLevel.HIGH)
                .count();
        long mediumCount = savedEvents.stream()
                .filter(e -> e.getRiskLevel() == ConjunctionEvent.RiskLevel.MEDIUM)
                .count();
        long lowCount = savedEvents.stream()
                .filter(e -> e.getRiskLevel() == ConjunctionEvent.RiskLevel.LOW)
                .count();

        log.info("Analysis complete: {} total events (Critical: {}, High: {}, Medium: {}, Low: {})",
                savedEvents.size(), criticalCount, highCount, mediumCount, lowCount);

        return savedEvents;
    }

    public List<ConjunctionEvent> getUpcomingEvents(Integer noradId, int daysAhead) {
        Optional<Satellite> satelliteOpt = satelliteRepository.findByNoradId(noradId);
        if(satelliteOpt.isEmpty()){
            throw new IllegalArgumentException("Satellite not found: " + noradId);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = now.plusDays(daysAhead);

        return conjunctionEventRepository.findUpcomingEventsForPrimary(
                satelliteOpt.get(),
                now,
                endTime
        );
    }

    public List<ConjunctionEvent> getHighRiskEvents(Integer noradId) {
        Optional<Satellite> satelliteOpt = satelliteRepository.findByNoradId(noradId);
        if(satelliteOpt.isEmpty()){
            throw new IllegalArgumentException("Satellite not found: " + noradId);
        }

        List<ConjunctionEvent.RiskLevel> highRiskLevels = List.of(
                ConjunctionEvent.RiskLevel.CRITICAL,
                ConjunctionEvent.RiskLevel.HIGH
        );

        return conjunctionEventRepository.findByPrimaryAndRiskLevels(
                satelliteOpt.get(),
                highRiskLevels,
                LocalDateTime.now()
        );
    }

    @Transactional
    public void cleanupOldEvents(int daysToKeep){
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToKeep);
        conjunctionEventRepository.deleteOldEvents(cutoffDate);
        log.info("Deleted conjunction events older than {}", cutoffDate);
    }
}