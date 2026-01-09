package com.orbit.repository;

import com.orbit.entity.CollisionAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CollisionAlertRepository extends JpaRepository<CollisionAlert, Long> {
    List<CollisionAlert> findByIsResolvedFalse();
    List<CollisionAlert> findByAlertLevelAndIsResolvedFalse(String  alertLevel);
    @Query("SELECT ca FROM CollisionAlert ca WHERE " +
            "((ca.satellite1Id = :sat1Id AND ca.satellite2Id = :sat2Id) OR " +
            "(ca.satellite1Id = :sat2Id AND ca.satellite2Id = :sat1Id)) AND " +
            "ca.tca = :tca")
    Optional<CollisionAlert> findBySatellitePairAndTca(
            @Param("sat1Id") Long satellite1Id,
            @Param("sat2Id") Long satellite2Id,
            @Param("tca") LocalDateTime tca
    );
    List<CollisionAlert> findByTcaBetween(LocalDateTime start, LocalDateTime end);
    @Query("SELECT ca FROM CollisionAlert ca WHERE ca.satellite1Id = :satId OR ca.satellite2Id = :satId")
    List<CollisionAlert> findBySatelliteId(@Param("satId") Long satelliteId);
}
