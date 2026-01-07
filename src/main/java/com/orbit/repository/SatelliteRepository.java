package com.orbit.repository;

import com.orbit.entity.Satellite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SatelliteRepository extends JpaRepository<Satellite, Long> {
    Optional<Satellite> findByNoradId(Integer  noradId);
    boolean existsByNoradId(Integer noradId);
}
