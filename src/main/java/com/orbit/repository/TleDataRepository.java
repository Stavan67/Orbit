package com.orbit.repository;

import com.orbit.entity.TleData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TleDataRepository extends JpaRepository<TleData, Long> {
    List<TleData> findBySatelliteId(Long satelliteId);
    @Query("SELECT t FROM TleData t WHERE t.satelliteId = :satelliteId AND t.isCurrent = true")
    Optional<TleData> findCurrentTleBySatelliteId(Long satelliteId);
    List<TleData> findByIsCurrentTrue();
}
