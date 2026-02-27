package com.orbit.service;

import com.orbit.config.SpaceTrackConfig;
import com.orbit.dto.SpaceTrackTleDto;
import com.orbit.entity.Satellite;
import com.orbit.entity.TleData;
import com.orbit.repository.SatelliteRepository;
import com.orbit.repository.TleDataRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpaceTrackService {

    private final SpaceTrackConfig config;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final SatelliteRepository satelliteRepository;
    private final TleDataRepository tleDataRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String authCookie;

    private void authenticate() throws IOException, InterruptedException {
        String loginUrl = config.getBaseUrl() + "/ajaxauth/login";
        String credentials = String.format("identity=%s&password=%s",
                URLEncoder.encode(config.getUsername(), StandardCharsets.UTF_8),
                URLEncoder.encode(config.getPassword(), StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(loginUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(credentials))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            authCookie = response.headers().firstValue("Set-Cookie").orElse("");
            log.info("Successfully authenticated with Space-Track");
        } else {
            throw new RuntimeException("Authentication failed: " + response.statusCode());
        }
    }

    public String getOrRefreshAuthCookie() throws IOException, InterruptedException {
        if (authCookie == null || authCookie.isBlank()) {
            authenticate();
        }
        return authCookie;
    }

    @Transactional
    public void fetchAndSaveLatestTles() throws IOException, InterruptedException {
        log.info("Starting bulk TLE fetch from Space-Track...");
        authenticate();

        String query = config.getBaseUrl()
                + "/basicspacedata/query/class/gp/EPOCH/%3Enow-30/orderby/NORAD_CAT_ID,EPOCH/format/json";

        List<SpaceTrackTleDto> tleList = executeQuery(query);
        log.info("Fetched {} TLE records from Space-Track", tleList.size());
        saveTleDataInBatch(tleList);
    }

    @Transactional
    public void fetchAndSaveTleByNoradId(Integer noradId) throws IOException, InterruptedException {
        log.info("Fetching TLE for NORAD {}", noradId);
        authenticate();

        // FIX: "EPOCH desc" contains a space which is illegal in a URI path.
        // Must be percent-encoded as "EPOCH%20desc" to avoid URISyntaxException.
        String query = config.getBaseUrl()
                + String.format(
                "/basicspacedata/query/class/gp/NORAD_CAT_ID/%d/orderby/EPOCH%%20desc/limit/1/format/json",
                noradId);

        List<SpaceTrackTleDto> tleList = executeQuery(query);
        if (!tleList.isEmpty()) {
            saveTleDataInBatch(tleList);
            log.info("Successfully saved TLE for NORAD {}", noradId);
        } else {
            log.warn("No TLE data found for NORAD {}", noradId);
        }
    }

    private List<SpaceTrackTleDto> executeQuery(String url)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Cookie", authCookie)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } else {
            throw new RuntimeException("Space-Track query failed: HTTP " + response.statusCode());
        }
    }

    private void saveTleDataInBatch(List<SpaceTrackTleDto> dtoList) {
        log.info("Processing {} TLE records...", dtoList.size());

        Map<Integer, SpaceTrackTleDto> latestByNorad = dtoList.stream()
                .collect(Collectors.groupingBy(
                        SpaceTrackTleDto::getNoradCatId,
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparing(SpaceTrackTleDto::getEpoch)),
                                opt -> opt.orElse(null))));

        Set<Integer> noradIds = latestByNorad.keySet();

        List<Satellite> existing = satelliteRepository.findAllByNoradIdIn(noradIds);
        Map<Integer, Satellite> satMap = existing.stream()
                .collect(Collectors.toMap(Satellite::getNoradId, s -> s));

        List<Satellite> newSats = noradIds.stream()
                .filter(id -> !satMap.containsKey(id))
                .map(id -> createSatelliteFromDto(latestByNorad.get(id)))
                .toList();

        if (!newSats.isEmpty()) {
            satelliteRepository.saveAll(newSats)
                    .forEach(s -> satMap.put(s.getNoradId(), s));
        }

        Set<Satellite> satSet = new HashSet<>(satMap.values());
        Map<Long, TleData> existingTleMap = tleDataRepository.findAllBySatelliteIn(satSet)
                .stream()
                .collect(Collectors.toMap(t -> t.getSatellite().getSatelliteId(), t -> t, (a, b) -> a));

        List<TleData> toSave = new ArrayList<>();
        int updated = 0, created = 0;

        for (Map.Entry<Integer, SpaceTrackTleDto> entry : latestByNorad.entrySet()) {
            Satellite sat = satMap.get(entry.getKey());
            if (sat == null) continue;

            TleData existing2 = existingTleMap.get(sat.getSatelliteId());
            if (existing2 != null) {
                populateTleFields(existing2, entry.getValue());
                toSave.add(existing2);
                updated++;
            } else {
                toSave.add(createTleDataFromDto(entry.getValue(), sat));
                created++;
            }
        }

        tleDataRepository.saveAll(toSave);
        log.info("TLE batch complete: {} updated, {} created", updated, created);
    }

    private Satellite createSatelliteFromDto(SpaceTrackTleDto dto) {
        Satellite s = new Satellite();
        s.setName(dto.getObjectName());
        s.setNoradId(dto.getNoradCatId());
        s.setInternationalDesignator(dto.getIntldes());
        s.setObjectType(dto.getObjectType());
        s.setCountry(dto.getCountryCode());
        s.setIsActive(true);
        return s;
    }

    private TleData createTleDataFromDto(SpaceTrackTleDto dto, Satellite satellite) {
        TleData t = new TleData();
        t.setSatellite(satellite);
        populateTleFields(t, dto);
        return t;
    }

    private void populateTleFields(TleData tleData, SpaceTrackTleDto dto) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
        tleData.setLine1(dto.getTleLine1());
        tleData.setLine2(dto.getTleLine2());
        tleData.setEpoch(LocalDateTime.parse(dto.getEpoch(), fmt));
        tleData.setMeanMotion(dto.getMeanMotion());
        tleData.setEccentricity(dto.getEccentricity());
        tleData.setInclination(dto.getInclination());
        tleData.setRaan(dto.getRaOfAscNode());
        tleData.setArgumentOfPerigee(dto.getArgOfPericenter());
        tleData.setMeanAnomaly(dto.getMeanAnomaly());
        tleData.setClassification(dto.getClassificationType());
        tleData.setElementSetNumber(dto.getElementSetNo());
    }
}