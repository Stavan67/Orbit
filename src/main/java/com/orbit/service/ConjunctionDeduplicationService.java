package com.orbit.service;

import com.orbit.entity.ConjunctionEvent;
import com.orbit.repository.ConjunctionEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConjunctionDeduplicationService {

    private final ConjunctionEventRepository conjunctionEventRepository;

    private static final DateTimeFormatter TCA_HOUR_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHH");

    public String buildDedupKey(
            Integer primaryNoradId,
            Integer secondaryNoradId,
            LocalDateTime tca
    ) {
        int id1 = Math.min(primaryNoradId, secondaryNoradId);
        int id2 = Math.max(primaryNoradId, secondaryNoradId);
        String tcaHour = tca.format(TCA_HOUR_FMT);
        return id1 + "_" + id2 + "_" + tcaHour;
    }

    public Optional<ConjunctionEvent> findExisting(String dedupKey) {
        return conjunctionEventRepository.findByDedupKey(dedupKey);
    }

    public boolean shouldUpdate(ConjunctionEvent existing, ConjunctionEvent incoming) {
        if (Boolean.TRUE.equals(incoming.getCdmBased())
                && !Boolean.TRUE.equals(existing.getCdmBased())) {
            log.debug("Updating event {} with CDM-sourced data (previously TLE-estimated)",
                    existing.getDedupKey());
            return true;
        }

        Double existingPc  = existing.getProbabilityOfCollision();
        Double incomingPc  = incoming.getProbabilityOfCollision();
        if (existingPc != null && incomingPc != null && incomingPc > existingPc * 1.5) {
            log.debug("Updating event {} — incoming Pc {} > existing Pc {}",
                    existing.getDedupKey(),
                    String.format("%.3e", incomingPc),
                    String.format("%.3e", existingPc));

            return true;
        }

        if (incoming.getMissDistance() < existing.getMissDistance() * 0.90) {
            log.debug("Updating event {} — better miss distance {}m vs {}m",
                    existing.getDedupKey(),
                    String.format("%.0f", incoming.getMissDistance()),
                    String.format("%.0f", existing.getMissDistance()));
            return true;
        }

        return false;
    }

    public ConjunctionEvent mergeIntoExisting(
            ConjunctionEvent existing,
            ConjunctionEvent incoming
    ) {
        existing.setMissDistance(incoming.getMissDistance());
        existing.setRelativeVelocity(incoming.getRelativeVelocity());
        existing.setTca(incoming.getTca());
        existing.setRiskLevel(incoming.getRiskLevel());
        existing.setPrimaryAltitude(incoming.getPrimaryAltitude());
        existing.setSecondaryAltitude(incoming.getSecondaryAltitude());
        existing.setScreeningEpoch(incoming.getScreeningEpoch());
        existing.setProbabilityOfCollision(incoming.getProbabilityOfCollision());
        existing.setPrimarySigmaM(incoming.getPrimarySigmaM());
        existing.setSecondarySigmaM(incoming.getSecondarySigmaM());
        existing.setCombinedHardBodyRadiusM(incoming.getCombinedHardBodyRadiusM());
        existing.setCdmBased(incoming.getCdmBased());
        if (incoming.getCdmId() != null) {
            existing.setCdmId(incoming.getCdmId());
        }
        // Preserve how this conjunction was originally detected.
        // TLE_SCREENED is the stronger detection — never downgrade it to CDM_ONLY on update.
        if (existing.getDetectionSource() != ConjunctionEvent.DetectionSource.TLE_SCREENED
                && incoming.getDetectionSource() != null) {
            existing.setDetectionSource(incoming.getDetectionSource());
        }
        return existing;
    }
}