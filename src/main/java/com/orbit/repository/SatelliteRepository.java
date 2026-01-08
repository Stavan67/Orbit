package com.orbit.repository;

import com.orbit.entity.Satellite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface SatelliteRepository extends JpaRepository<Satellite, Long> {
    Optional<Satellite> findByNoradId(Integer noradId);
    boolean existsByNoradId(Integer noradId);
    List<Satellite> findAllByNoradIdIn(Set<Integer> noradIds);
}