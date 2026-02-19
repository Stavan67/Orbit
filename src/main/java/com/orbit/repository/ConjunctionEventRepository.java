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

@Repository
public interface ConjunctionEventRepository extends JpaRepository<ConjunctionEvent, Long> {

    List<ConjunctionEvent> findByPrimarySatelliteAndTcaAfter(
            Satellite primarySatellite,
            LocalDateTime afterTime
    );

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

    @Modifying
    @Query("DELETE FROM ConjunctionEvent ce WHERE ce.tca < :cutoffDate")
    void deleteOldEvents(@Param("cutoffDate") LocalDateTime cutoffDate);

    @Query("SELECT COUNT(ce) FROM ConjunctionEvent ce " +
            "WHERE ce.primarySatellite = :primary " +
            "AND ce.tca BETWEEN :start AND :end")
    long countEventsByPrimaryInWindow(
            @Param("primary") Satellite primary,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );
}