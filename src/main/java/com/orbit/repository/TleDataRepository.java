package com.orbit.repository;

import com.orbit.entity.Satellite;
import com.orbit.entity.TleData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface TleDataRepository extends JpaRepository<TleData, Long> {
    Optional<TleData> findBySatellite(Satellite satellite);

    List<TleData> findAllBySatelliteIn(Set<Satellite> satellites);
}