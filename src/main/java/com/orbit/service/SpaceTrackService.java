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
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
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
    private String authCookie;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        if(response.statusCode() == 200) {
            authCookie = response.headers().firstValue("Set-Cookie").orElse("");
            log.info("Successfully authenticated with Space-Track");
        } else{
            throw new RuntimeException("Authentication failed: " + response.statusCode());
        }
    }

    @Transactional
    public void fetchAndSaveLatestTles() throws IOException, InterruptedException {
        log.info("Starting to fetch TLE data from Space-Track...");
        authenticate();
        String query = config.getBaseUrl() +
                "/basicspacedata/query/class/gp/EPOCH/%3Enow-30/orderby/NORAD_CAT_ID,EPOCH/format/json";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(query))
                .header("Cookie", authCookie)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if(response.statusCode() == 200) {
            List<SpaceTrackTleDto> tleList = objectMapper.readValue(
                    response.body(),
                    new TypeReference<>() {
                    }
            );
            log.info("Fetched {} TLE records from Space-Track", tleList.size());
            saveTleDataInBatch(tleList);
        } else {
            throw new RuntimeException("Failed to fetch TLE data: " + response.statusCode());
        }
    }

    @Transactional
    public void fetchAndSaveTleByNoradId(Integer noradId) throws IOException, InterruptedException {
        log.info("Fetching TLE data for NORAD ID: {}", noradId);
        authenticate();
        String query = config.getBaseUrl() +
                String.format("/basicspacedata/query/class/gp/NORAD_CAT_ID/%d/orderby/EPOCH desc/limit/1/format/json", noradId);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(query))
                .header("Cookie", authCookie)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if(response.statusCode() == 200) {
            List<SpaceTrackTleDto> tleList = objectMapper.readValue(
                    response.body(),
                    new TypeReference<>() {
                    }
            );
            if(!tleList.isEmpty()) {
                saveTleDataInBatch(tleList);
                log.info("Successfully saved TLE for NORAD ID: {}", noradId);
            } else {
                log.warn("No TLE data found for NORAD ID: {}", noradId);
            }
        } else{
            throw new RuntimeException("Failed to fetch TLE data: " + response.statusCode());
        }
    }

    private void saveTleDataInBatch(List<SpaceTrackTleDto> dtoList) {
        log.info("Processing {} records in optimized batch mode...", dtoList.size());

        Map<Integer, SpaceTrackTleDto> latestTleByNorad = dtoList.stream()
                .collect(Collectors.groupingBy(
                        SpaceTrackTleDto::getNoradCatId,
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparing(SpaceTrackTleDto::getEpoch)),
                                opt -> opt.orElse(null)
                        )
                ));

        log.info("Filtered to {} unique satellites with latest TLE data", latestTleByNorad.size());

        Set<Integer> noradIds = latestTleByNorad.keySet();

        List<Satellite> existingSatellites = satelliteRepository.findAllByNoradIdIn(noradIds);
        Map<Integer, Satellite> satelliteMap = existingSatellites.stream()
                .collect(Collectors.toMap(Satellite::getNoradId, s -> s));

        log.info("Found {} existing satellites in database", existingSatellites.size());

        List<Satellite> newSatellites = new ArrayList<>();
        for (Integer noradId : noradIds) {
            if (!satelliteMap.containsKey(noradId)) {
                SpaceTrackTleDto dto = latestTleByNorad.get(noradId);
                Satellite newSat = createSatelliteFromDto(dto);
                newSatellites.add(newSat);
            }
        }

        if (!newSatellites.isEmpty()) {
            log.info("Inserting {} new satellites in batch...", newSatellites.size());
            List<Satellite> savedNewSats = satelliteRepository.saveAll(newSatellites);
            savedNewSats.forEach(s -> satelliteMap.put(s.getNoradId(), s));
        }

        Set<Satellite> satellites = new HashSet<>(satelliteMap.values());

        List<TleData> existingTles = tleDataRepository.findAllBySatelliteIn(satellites);
        Map<Long, TleData> existingTleMap = existingTles.stream()
                .collect(Collectors.toMap(tle -> tle.getSatellite().getSatelliteId(), t -> t, (t1, t2) -> t1));

        log.info("Found {} existing TLE records in database", existingTles.size());

        List<TleData> tlesToSave = new ArrayList<>();
        int updatedCount = 0;
        int createdCount = 0;

        for (Map.Entry<Integer, SpaceTrackTleDto> entry : latestTleByNorad.entrySet()) {
            Satellite satellite = satelliteMap.get(entry.getKey());
            if (satellite != null) {
                TleData existingTle = existingTleMap.get(satellite.getSatelliteId());

                if (existingTle != null) {
                    updateTleDataFromDto(existingTle, entry.getValue());
                    tlesToSave.add(existingTle);
                    updatedCount++;
                } else {
                    TleData newTle = createTleDataFromDto(entry.getValue(), satellite);
                    tlesToSave.add(newTle);
                    createdCount++;
                }

                if (tlesToSave.size() % 1000 == 0) {
                    log.info("Prepared {}/{} TLE records...", tlesToSave.size(), latestTleByNorad.size());
                }
            }
        }

        log.info("Saving {} TLE records ({} updates, {} new)...", tlesToSave.size(), updatedCount, createdCount);
        tleDataRepository.saveAll(tlesToSave);

        log.info("Successfully saved {} TLE records to database ({} updated, {} created)",
                tlesToSave.size(), updatedCount, createdCount);
    }

    private Satellite createSatelliteFromDto(SpaceTrackTleDto dto) {
        Satellite satellite = new Satellite();
        satellite.setName(dto.getObjectName());
        satellite.setNoradId(dto.getNoradCatId());
        satellite.setInternationalDesignator(dto.getIntldes());
        satellite.setObjectType(dto.getObjectType());
        satellite.setCountry(dto.getCountryCode());
        satellite.setIsActive(true);
        return satellite;
    }

    private TleData createTleDataFromDto(SpaceTrackTleDto dto, Satellite satellite) {
        TleData tleData = new TleData();
        tleData.setSatellite(satellite);
        populateTleFields(tleData, dto);
        return tleData;
    }

    private void updateTleDataFromDto(TleData existingTle, SpaceTrackTleDto dto) {
        populateTleFields(existingTle, dto);
    }

    private void populateTleFields(TleData tleData, SpaceTrackTleDto dto) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
        LocalDateTime epochDate = LocalDateTime.parse(dto.getEpoch(), formatter);

        tleData.setLine1(dto.getTleLine1());
        tleData.setLine2(dto.getTleLine2());
        tleData.setEpoch(epochDate);
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