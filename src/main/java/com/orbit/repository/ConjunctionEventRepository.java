package com.orbit.repository;

import com.orbit.entity.ConjunctionEvent;
import com.orbit.entity.Satellite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConjunctionEventRepository extends JpaRepository<ConjunctionEvent, Long> {
    Optional<ConjunctionEvent> findByDedupKey(String dedupKey);
    @Query("SELECT ce FROM ConjunctionEvent ce " +
            "WHERE ce.primarySatellite = :primary " +
            "AND ce.tca BETWEEN :startTime AND :endTime " +
            "ORDER BY ce.missDistance ASC")
    List<ConjunctionEvent> findUpcomingEventsForPrimary(
            @Param("primary") Satellite primary,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    @Query("SELECT ce FROM ConjunctionEvent ce " +
            "WHERE ce.primarySatellite = :primary " +
            "AND ce.riskLevel IN :riskLevels " +
            "AND ce.tca > :now " +
            "ORDER BY ce.tca ASC")
    List<ConjunctionEvent> findByPrimaryAndRiskLevels(
            @Param("primary") Satellite primary,
            @Param("riskLevels") List<ConjunctionEvent.RiskLevel> riskLevels,
            @Param("now") LocalDateTime now
    );

    @Query("SELECT ce FROM ConjunctionEvent ce " +
            "WHERE ce.primarySatellite = :primary " +
            "AND ce.probabilityOfCollision >= :minPc " +
            "AND ce.tca > :now " +
            "ORDER BY ce.probabilityOfCollision DESC")
    List<ConjunctionEvent> findByPrimaryAndMinPc(
            @Param("primary") Satellite primary,
            @Param("minPc") Double minPc,
            @Param("now") LocalDateTime now
    );

    @Query("SELECT ce FROM ConjunctionEvent ce " +
            "WHERE ce.primarySatellite = :primary " +
            "AND ce.cdmBased = true " +
            "AND ce.tca > :now " +
            "ORDER BY ce.tca ASC")
    List<ConjunctionEvent> findCdmBackedEvents(
            @Param("primary") Satellite primary,
            @Param("now") LocalDateTime now
    );

    @Query("SELECT MAX(e.createdAt) FROM ConjunctionEvent e WHERE e.primarySatellite.noradId = :noradId")
    Optional<LocalDateTime> findLastAnalysisTimeByPrimaryNoradId(@Param("noradId") Integer noradId);

    @Modifying
    @Query("DELETE FROM ConjunctionEvent ce WHERE ce.tca < :cutoffDate")
    void deleteOldEvents(@Param("cutoffDate") LocalDateTime cutoffDate);
}