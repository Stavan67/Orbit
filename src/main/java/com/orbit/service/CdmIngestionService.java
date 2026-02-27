package com.orbit.service;

import com.orbit.config.SpaceTrackConfig;
import com.orbit.dto.SpaceTrackCdmDto;
import com.orbit.entity.CdmData;
import com.orbit.repository.CdmDataRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CdmIngestionService {

    private final SpaceTrackConfig config;
    private final CdmDataRepository cdmDataRepository;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final DateTimeFormatter[] CDM_DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    };

    @Transactional
    public List<CdmData> fetchAndStoreCdmsForSatellite(Integer noradId, String authCookie)
            throws IOException, InterruptedException {

        log.info("Fetching CDMs from Space-Track for NORAD {}", noradId);

        String url = config.getBaseUrl()
                + "/basicspacedata/query/class/cdm_public"
                + "/SAT_1_ID/" + noradId
                + "/TCA/%3Enow"
                + "/orderby/TCA%20asc"
                + "/limit/100"
                + "/format/json";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Cookie", authCookie)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("CDM fetch failed for NORAD {} — HTTP {}: {}",
                    noradId, response.statusCode(), response.body());
            throw new RuntimeException("CDM fetch failed: HTTP " + response.statusCode());
        }

        List<SpaceTrackCdmDto> dtos = objectMapper.readValue(
                response.body(), new TypeReference<>() {});

        log.info("Received {} CDM records for NORAD {}", dtos.size(), noradId);

        List<CdmData> saved = new ArrayList<>();
        int skipped = 0;

        for (SpaceTrackCdmDto dto : dtos) {
            if (dto.getCdmId() == null) continue;

            if (cdmDataRepository.existsByCdmId(dto.getCdmId())) {
                skipped++;
                continue;
            }

            CdmData cdmData = mapToEntity(dto);
            if (cdmData != null) {
                saved.add(cdmDataRepository.save(cdmData));
            }
        }

        log.info("CDM ingestion for NORAD {}: {} new, {} already existed",
                noradId, saved.size(), skipped);

        saved.stream()
                .filter(c -> c.getPc() != null && c.getPc() >= 1e-4)
                .forEach(c -> log.warn(
                        "[CDM ALERT] High Pc CDM ingested: NORAD {} vs {} | TCA={} | Pc={} | Emergency={}",
                        c.getNoradId1(),
                        c.getNoradId2(),
                        c.getTca(),
                        String.format("%.3e", c.getPc()),
                        c.getEmergencyReportable()));

        return saved;
    }

    @Transactional
    public List<CdmData> fetchAndStoreCdmsForSatellites(
            List<Integer> noradIds,
            String authCookie
    ) {
        List<CdmData> allSaved = new ArrayList<>();
        for (Integer noradId : noradIds) {
            try {
                allSaved.addAll(fetchAndStoreCdmsForSatellite(noradId, authCookie));
            } catch (Exception e) {
                log.error("Failed to fetch CDMs for NORAD {}: {}", noradId, e.getMessage());
            }
        }
        return allSaved;
    }

    public List<CdmData> getStoredCdmsForSatellite(Integer noradId) {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        List<CdmData> asSat1 = cdmDataRepository.findByNoradId1AndTcaAfter(noradId, nowUtc);
        List<CdmData> asSat2 = cdmDataRepository.findByNoradId2AndTcaAfter(noradId, nowUtc);

        log.debug("CDM lookup for NORAD {}: {} as SAT_1, {} as SAT_2 (cutoff UTC={})",
                noradId, asSat1.size(), asSat2.size(), nowUtc);

        List<CdmData> combined = new ArrayList<>(asSat1);
        combined.addAll(asSat2);
        return combined;
    }

    private CdmData mapToEntity(SpaceTrackCdmDto dto) {
        LocalDateTime tca = parseDateTime(dto.getTca());
        if (tca == null) {
            log.warn("Skipping CDM {} — could not parse TCA '{}'", dto.getCdmId(), dto.getTca());
            return null;
        }

        CdmData entity = new CdmData();
        entity.setCdmId(dto.getCdmId());
        entity.setCdmCreated(parseDateTime(dto.getCreated()));
        entity.setNoradId1(dto.getSat1Id());
        entity.setNoradId2(dto.getSat2Id());
        entity.setSat1Name(dto.getSat1Name());
        entity.setSat2Name(dto.getSat2Name());
        entity.setTca(tca);

        if (dto.getMinRng() != null) {
            entity.setMissDistanceM(dto.getMinRng());
        }

        if (dto.getRelSpeed() != null) {
            entity.setRelativeSpeedMs(dto.getRelSpeed() * 1000.0);
        }

        entity.setPc(dto.getPc());
        entity.setEmergencyReportable(dto.getEmergencyReportable());
        entity.setSat1Type(dto.getSat1Type());
        entity.setSat2Type(dto.getSat2Type());
        entity.setSat1Rcs(dto.getSat1Rcs());
        entity.setSat2Rcs(dto.getSat2Rcs());

        return entity;
    }

    private LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (DateTimeFormatter fmt : CDM_DATE_FORMATS) {
            try {
                return LocalDateTime.parse(raw.trim(), fmt);
            } catch (DateTimeParseException ignored) {}
        }
        log.warn("Could not parse CDM datetime string: '{}'", raw);
        return null;
    }
}