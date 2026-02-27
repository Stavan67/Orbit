package com.orbit.service;

import com.orbit.entity.ConjunctionEvent.RiskLevel;
import com.orbit.dto.ConjunctionResult;
import com.orbit.dto.PcResult;
import com.orbit.entity.CdmData;
import com.orbit.entity.ConjunctionEvent;
import com.orbit.entity.Satellite;
import com.orbit.entity.TleData;
import com.orbit.repository.ConjunctionEventRepository;
import com.orbit.repository.SatelliteRepository;
import com.orbit.repository.TleDataRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ProbabilityOfCollisionService pcService;
    private final ConjunctionDeduplicationService deduplicationService;
    private final CdmIngestionService cdmIngestionService;

    private AlertingService alertingService;

    @Value("${conjunction.filter.raan.tolerance.deg:45.0}")
    private double raanToleranceDeg;

    @Value("${conjunction.filter.raan.enabled:true}")
    private boolean useRaanFilter;

    @Value("${conjunction.cdm.tca.window.hours:6}")
    private int cdmTcaWindowHours;

    @Transactional
    public List<ConjunctionEvent> analyzeConjunctions(Integer primaryNoradId) {
        log.info("Starting conjunction analysis for NORAD {} ", primaryNoradId);

        Satellite primarySat = satelliteRepository.findByNoradId(primaryNoradId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Primary satellite not found: " + primaryNoradId));

        TleData primaryTle = tleDataRepository.findBySatellite(primarySat)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No TLE data for satellite: " + primaryNoradId));

        LocalDateTime screeningEpoch = LocalDateTime.now();
        warnIfTleStale(primaryTle, "Primary");

        List<CdmData> availableCdms = cdmIngestionService.getStoredCdmsForSatellite(primaryNoradId);

        Map<String, List<CdmData>> cdmsByPairKey = indexCdmsByPairKey(availableCdms);
        log.info("Loaded {} stored CDMs for NORAD {} across {} unique pairs",
                availableCdms.size(), primaryNoradId, cdmsByPairKey.size());

        List<TleData> allTles = tleDataRepository.findAll();
        Map<Integer, TleData> tleByNorad = allTles.stream()
                .collect(Collectors.toMap(
                        t -> t.getSatellite().getNoradId(),
                        t -> t,
                        (a, b) -> a));
        log.info("Total satellites in database: {}", allTles.size());

        List<TleData> candidates = filterCandidates(primaryTle, allTles);
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        List<ConjunctionResult> screeningResults = screeningService.screenMultiplePairs(
                primaryTle, candidates, screeningEpoch);

        if (screeningResults.isEmpty()) {
            log.info("No conjunctions detected within screening parameters for NORAD {}", primaryNoradId);
            return new ArrayList<>();
        }

        log.info("Screening found {} raw conjunctions — computing Pc and deduplicating...",
                screeningResults.size());

        List<ConjunctionEvent> eventsToSave = new ArrayList<>();
        int duplicatesSkipped = 0;
        int duplicatesUpdated = 0;
        int secondaryNotFound = 0;

        for (ConjunctionResult result : screeningResults) {
            Optional<Satellite> secondarySatOpt =
                    satelliteRepository.findByNoradId(result.getSecondaryNoradId());

            if (secondarySatOpt.isEmpty()) {
                log.warn("Secondary satellite not found in DB: NORAD {}", result.getSecondaryNoradId());
                secondaryNotFound++;
                continue;
            }

            PcResult pcResult = computePc(
                    result, primaryTle, tleByNorad.get(result.getSecondaryNoradId()),
                    cdmsByPairKey);

            RiskLevel riskLevel = riskAssessmentService.assessRisk(result, pcResult, screeningEpoch);

            ConjunctionEvent event = buildEvent(
                    primarySat, secondarySatOpt.get(), result, pcResult, riskLevel,
                    screeningEpoch, cdmsByPairKey);

            event.setDetectionSource(ConjunctionEvent.DetectionSource.TLE_SCREENED);

            String dedupKey = deduplicationService.buildDedupKey(
                    primaryNoradId, result.getSecondaryNoradId(), result.getTca());
            event.setDedupKey(dedupKey);

            Optional<ConjunctionEvent> existing = deduplicationService.findExisting(dedupKey);

            if (existing.isPresent()) {
                if (deduplicationService.shouldUpdate(existing.get(), event)) {
                    ConjunctionEvent updated =
                            deduplicationService.mergeIntoExisting(existing.get(), event);
                    eventsToSave.add(updated);
                    duplicatesUpdated++;
                } else {
                    duplicatesSkipped++;
                }
            } else {
                eventsToSave.add(event);
            }

            if (riskAssessmentService.requiresAttention(riskLevel)) {
                log.warn(riskAssessmentService.generateRiskSummary(result, riskLevel, pcResult));
            }
        }

        log.info("Deduplication: {} new | {} updated | {} skipped (already current) | {} secondary not found",
                eventsToSave.size() - duplicatesUpdated,
                duplicatesUpdated,
                duplicatesSkipped,
                secondaryNotFound);

        Set<String> screenedDedupKeys = screeningResults.stream()
                .map(r -> deduplicationService.buildDedupKey(
                        primaryNoradId, r.getSecondaryNoradId(), r.getTca()))
                .collect(Collectors.toSet());

        List<ConjunctionEvent> cdmOnlyEvents = buildCdmOnlyEvents(
                primarySat, primaryNoradId, cdmsByPairKey, screenedDedupKeys, screeningEpoch);

        if (!cdmOnlyEvents.isEmpty()) {
            log.warn("[CDM_ONLY] {} CDM-sourced event(s) added for NORAD {} — NOT independently " +
                    "detected by TLE screening. Source: Space-Track.", cdmOnlyEvents.size(), primaryNoradId);
            eventsToSave.addAll(cdmOnlyEvents);
        }

        List<ConjunctionEvent> savedEvents = conjunctionEventRepository.saveAll(eventsToSave);

        if (alertingService != null && !savedEvents.isEmpty()) {
            alertingService.evaluateAndAlertBatch(savedEvents, primaryNoradId);
        }
        logSummary(savedEvents);
        return savedEvents;
    }

    public List<ConjunctionEvent> getUpcomingEvents(Integer noradId, int daysAhead) {
        Satellite satellite = satelliteRepository.findByNoradId(noradId)
                .orElseThrow(() -> new IllegalArgumentException("Satellite not found: " + noradId));
        LocalDateTime now = LocalDateTime.now();
        return conjunctionEventRepository.findUpcomingEventsForPrimary(
                satellite, now, now.plusDays(daysAhead));
    }

    public List<ConjunctionEvent> getHighRiskEvents(Integer noradId) {
        Satellite satellite = satelliteRepository.findByNoradId(noradId)
                .orElseThrow(() -> new IllegalArgumentException("Satellite not found: " + noradId));
        return conjunctionEventRepository.findByPrimaryAndRiskLevels(
                satellite,
                List.of(ConjunctionEvent.RiskLevel.CRITICAL, ConjunctionEvent.RiskLevel.HIGH),
                LocalDateTime.now());
    }

    @Transactional
    public void cleanupOldEvents(int daysToKeep) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysToKeep);
        conjunctionEventRepository.deleteOldEvents(cutoff);
        log.info("Deleted conjunction events with TCA before {}", cutoff);
    }

    private List<TleData> filterCandidates(TleData primaryTle, List<TleData> allTles) {
        List<TleData> candidates = filterService.filterCandidates(primaryTle, allTles);
        if (candidates.isEmpty()) {
            log.info("No candidates after altitude/inclination filter");
            return candidates;
        }

        if (useRaanFilter && candidates.size() > 100) {
            candidates = filterService.refineByRaan(primaryTle, candidates, raanToleranceDeg);
        }
        if (candidates.isEmpty()) return candidates;

        candidates = filterService.filterOutCoLocated(primaryTle, candidates);
        log.info("{} candidates after all filters", candidates.size());
        return candidates;
    }

    private PcResult computePc(
            ConjunctionResult result,
            TleData primaryTle,
            TleData secondaryTle,
            Map<String, List<CdmData>> cdmsByPairKey
    ) {
        String pairKey = cdmPairKey(result.getPrimaryNoradId(), result.getSecondaryNoradId());
        CdmData matchedCdm = findCdmForPass(cdmsByPairKey.get(pairKey), result.getTca());

        if (matchedCdm != null && matchedCdm.getPc() != null) {
            log.debug("Using CDM Pc={} for pair {}-{} (CDM TCA={}, TLE TCA={}, diff={}h)",
                    matchedCdm.getPc(),
                    result.getPrimaryNoradId(),
                    result.getSecondaryNoradId(),
                    matchedCdm.getTca(),
                    result.getTca(),
                    Math.abs(ChronoUnit.HOURS.between(matchedCdm.getTca(), result.getTca())));
            return pcService.fromCdm(matchedCdm.getPc());
        }

        if (secondaryTle == null) {
            log.warn("No secondary TLE found for Pc calculation, using 7-day age default");
            return pcService.computeFromTle(result, primaryTle.getEpoch(), null);
        }

        return pcService.computeFromTle(result, primaryTle.getEpoch(), secondaryTle.getEpoch());
    }

    private ConjunctionEvent buildEvent(
            Satellite primarySat,
            Satellite secondarySat,
            ConjunctionResult result,
            PcResult pcResult,
            ConjunctionEvent.RiskLevel riskLevel,
            LocalDateTime screeningEpoch,
            Map<String, List<CdmData>> cdmsByPairKey
    ) {
        ConjunctionEvent event = new ConjunctionEvent();
        event.setPrimarySatellite(primarySat);
        event.setSecondarySatellite(secondarySat);
        event.setTca(result.getTca());
        event.setMissDistance(result.getMissDistance());
        event.setRelativeVelocity(result.getRelativeVelocity());
        event.setRiskLevel(riskLevel);
        event.setPrimaryAltitude(result.getPrimaryAltitude());
        event.setSecondaryAltitude(result.getSecondaryAltitude());
        event.setScreeningEpoch(screeningEpoch);
        event.setProbabilityOfCollision(pcResult.getPc());
        event.setPrimarySigmaM(Double.isNaN(pcResult.getSigmaXm()) ? null : pcResult.getSigmaXm());
        event.setSecondarySigmaM(Double.isNaN(pcResult.getSigmaYm()) ? null : pcResult.getSigmaYm());
        event.setCombinedHardBodyRadiusM(
                Double.isNaN(pcResult.getCombinedHbrM()) ? null : pcResult.getCombinedHbrM());

        boolean isCdmBased = "CDM_DIRECT".equals(pcResult.getMethod());
        event.setCdmBased(isCdmBased);

        if (isCdmBased) {
            String pairKey = cdmPairKey(result.getPrimaryNoradId(), result.getSecondaryNoradId());
            CdmData matchedCdm = findCdmForPass(cdmsByPairKey.get(pairKey), result.getTca());
            if (matchedCdm != null) {
                event.setCdmId(matchedCdm.getCdmId());
            }
        }

        return event;
    }

    private CdmData findCdmForPass(List<CdmData> cdms, LocalDateTime conjunctionTca) {
        if (cdms == null || cdms.isEmpty()) return null;

        return cdms.stream()
                .filter(cdm -> cdm.getTca() != null)
                .filter(cdm -> Math.abs(ChronoUnit.HOURS.between(cdm.getTca(), conjunctionTca))
                        <= cdmTcaWindowHours)
                .min(Comparator
                        .comparingLong((CdmData cdm) ->
                                Math.abs(ChronoUnit.SECONDS.between(cdm.getTca(), conjunctionTca)))
                        .thenComparing(Comparator.comparing(CdmData::getCdmId).reversed()))
                .orElse(null);
    }


    private Map<String, List<CdmData>> indexCdmsByPairKey(List<CdmData> cdms) {
        return cdms.stream()
                .collect(Collectors.groupingBy(
                        c -> cdmPairKey(c.getNoradId1(), c.getNoradId2())
                ));
    }

    private String cdmPairKey(Integer norad1, Integer norad2) {
        int lo = Math.min(norad1, norad2);
        int hi = Math.max(norad1, norad2);
        return lo + "_" + hi;
    }

    private List<ConjunctionEvent> buildCdmOnlyEvents(
            Satellite primarySat,
            Integer primaryNoradId,
            Map<String, List<CdmData>> cdmsByPairKey,
            Set<String> screenedDedupKeys,
            LocalDateTime screeningEpoch
    ) {
        Map<String, List<CdmData>> cdmsByDedupKey = new java.util.HashMap<>();
        Map<String, Integer> secondaryNoradByDedupKey = new java.util.HashMap<>();

        for (Map.Entry<String, List<CdmData>> entry : cdmsByPairKey.entrySet()) {
            for (CdmData cdm : entry.getValue()) {
                if (cdm.getTca() == null || cdm.getPc() == null) continue;

                Integer secondaryNorad = cdm.getNoradId1().equals(primaryNoradId)
                        ? cdm.getNoradId2() : cdm.getNoradId1();

                String dedupKey = deduplicationService.buildDedupKey(
                        primaryNoradId, secondaryNorad, cdm.getTca());

                cdmsByDedupKey.computeIfAbsent(dedupKey, k -> new ArrayList<>()).add(cdm);
                secondaryNoradByDedupKey.put(dedupKey, secondaryNorad);
            }
        }

        List<ConjunctionEvent> cdmOnlyEvents = new ArrayList<>();

        for (Map.Entry<String, List<CdmData>> entry : cdmsByDedupKey.entrySet()) {
            String dedupKey = entry.getKey();

            if (screenedDedupKeys.contains(dedupKey)) continue;

            if (deduplicationService.findExisting(dedupKey).isPresent()) continue;

            CdmData bestCdm = entry.getValue().stream()
                    .max(Comparator.comparingDouble(CdmData::getPc)
                            .thenComparing(CdmData::getCdmId))
                    .orElse(null);
            if (bestCdm == null) continue;

            Integer secondaryNorad = secondaryNoradByDedupKey.get(dedupKey);
            Optional<Satellite> secondarySatOpt = satelliteRepository.findByNoradId(secondaryNorad);
            if (secondarySatOpt.isEmpty()) continue;

            log.warn("[CDM_ONLY] Pair {}-{} | TCA={} | Pc={} | " +
                            "NOT detected by TLE screening — event sourced from Space-Track CDM {}.",
                    primaryNoradId, secondaryNorad,
                    bestCdm.getTca(),
                    String.format("%.3e", bestCdm.getPc()),
                    bestCdm.getCdmId());

            ConjunctionEvent event = new ConjunctionEvent();
            event.setPrimarySatellite(primarySat);
            event.setSecondarySatellite(secondarySatOpt.get());
            event.setTca(bestCdm.getTca());
            event.setMissDistance(bestCdm.getMissDistanceM() != null ? bestCdm.getMissDistanceM() : 0.0);
            event.setRelativeVelocity(bestCdm.getRelativeSpeedMs() != null ? bestCdm.getRelativeSpeedMs() : 0.0);
            event.setPrimaryAltitude(null);
            event.setSecondaryAltitude(null);
            event.setScreeningEpoch(screeningEpoch);
            event.setProbabilityOfCollision(bestCdm.getPc());
            event.setCdmBased(true);
            event.setCdmId(bestCdm.getCdmId());
            event.setDetectionSource(ConjunctionEvent.DetectionSource.CDM_ONLY);
            event.setDedupKey(dedupKey);
            event.setRiskLevel(riskLevelFromPc(bestCdm.getPc()));

            cdmOnlyEvents.add(event);
        }

        return cdmOnlyEvents;
    }

    private RiskLevel riskLevelFromPc(double pc) {
        if (pc >= 1e-2) return RiskLevel.CRITICAL;
        if (pc >= 1e-3) return RiskLevel.HIGH;
        if (pc >= 1e-4) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDashboard(Integer noradId) {
        Satellite satellite = satelliteRepository.findByNoradId(noradId)
                .orElseThrow(() -> new IllegalArgumentException("Satellite not found: " + noradId));

        LocalDateTime now = LocalDateTime.now();
        List<ConjunctionEvent> events = conjunctionEventRepository.findByPrimaryAndRiskLevels(
                satellite,
                List.of(RiskLevel.CRITICAL, RiskLevel.HIGH, RiskLevel.MEDIUM),
                now);

        List<Map<String, Object>> critical = new ArrayList<>();
        List<Map<String, Object>> high     = new ArrayList<>();
        List<Map<String, Object>> medium   = new ArrayList<>();

        long cdmBacked = 0;
        long cdmOnly   = 0;

        for (ConjunctionEvent e : events) {
            Map<String, Object> dto = buildEventDto(e, now);
            switch (e.getRiskLevel()) {
                case CRITICAL -> critical.add(dto);
                case HIGH     -> high.add(dto);
                case MEDIUM   -> medium.add(dto);
                default       -> {}
            }
            if (Boolean.TRUE.equals(e.getCdmBased())) cdmBacked++;
            if (e.getDetectionSource() == ConjunctionEvent.DetectionSource.CDM_ONLY) cdmOnly++;
        }

        Map<String, Object> counts = new java.util.LinkedHashMap<>();
        counts.put("critical", critical.size());
        counts.put("high", high.size());
        counts.put("medium", medium.size());
        counts.put("total", events.size());
        counts.put("cdmBacked", cdmBacked);
        counts.put("cdmOnly", cdmOnly);

        Map<String, Object> eventsByRisk = new java.util.LinkedHashMap<>();
        eventsByRisk.put("critical", critical);
        eventsByRisk.put("high", high);
        eventsByRisk.put("medium", medium);

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("primaryNorad", noradId);
        response.put("primaryName", satellite.getName());
        response.put("generatedAt", now.toString());
        response.put("counts", counts);
        response.put("events", eventsByRisk);
        return response;
    }

    private Map<String, Object> buildEventDto(ConjunctionEvent e, LocalDateTime now) {
        double hoursToTca = ChronoUnit.MINUTES.between(now, e.getTca()) / 60.0;
        double pc = e.getProbabilityOfCollision() != null ? e.getProbabilityOfCollision() : 0.0;

        Map<String, Object> dto = new java.util.LinkedHashMap<>();
        dto.put("eventId", e.getEventId());
        dto.put("secondaryNorad", e.getSecondarySatellite().getNoradId());
        dto.put("secondaryName", e.getSecondarySatellite().getName());
        dto.put("tca", e.getTca().toString());
        dto.put("hoursToTca", Math.round(hoursToTca * 10.0) / 10.0);
        dto.put("missDistanceM", e.getMissDistance() != null ? Math.round(e.getMissDistance()) : null);
        dto.put("relativeVelocityMs", e.getRelativeVelocity() != null
                ? Math.round(e.getRelativeVelocity() * 10.0) / 10.0 : null);
        dto.put("probabilityOfCollision", pc);
        dto.put("pcDisplay", pc == 0.0 ? "< 1e-30" : String.format("%.3e", pc));
        dto.put("riskLevel", e.getRiskLevel().name());
        dto.put("detectionSource", e.getDetectionSource() != null
                ? e.getDetectionSource().name() : "TLE_SCREENED");
        dto.put("cdmBased", Boolean.TRUE.equals(e.getCdmBased()));
        dto.put("primaryAltitudeKm", e.getPrimaryAltitude());
        dto.put("secondaryAltitudeKm", e.getSecondaryAltitude());
        return dto;
    }

    private void warnIfTleStale(TleData tle, String label) {
        if (tle.getEpoch() != null) {
            long ageDays = ChronoUnit.DAYS.between(tle.getEpoch(), LocalDateTime.now());
            if (ageDays > propagationService.getMaxTleAgeDays()) {
                log.warn("{} satellite NORAD {} TLE is {} days old — results may be unreliable",
                        label, tle.getSatellite().getNoradId(), ageDays);
            }
        }
    }

    private void logSummary(List<ConjunctionEvent> events) {
        long critical = events.stream().filter(e -> e.getRiskLevel() == ConjunctionEvent.RiskLevel.CRITICAL).count();
        long high     = events.stream().filter(e -> e.getRiskLevel() == ConjunctionEvent.RiskLevel.HIGH).count();
        long medium   = events.stream().filter(e -> e.getRiskLevel() == ConjunctionEvent.RiskLevel.MEDIUM).count();
        long low      = events.stream().filter(e -> e.getRiskLevel() == ConjunctionEvent.RiskLevel.LOW).count();
        long cdmBased = events.stream().filter(e -> Boolean.TRUE.equals(e.getCdmBased())).count();

        log.info("Analysis complete: {} events (C:{} H:{} M:{} L:{}) | {} CDM-backed",
                events.size(), critical, high, medium, low, cdmBased);
    }
}