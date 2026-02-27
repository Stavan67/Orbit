package com.orbit.repository;

import com.orbit.entity.CdmData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CdmDataRepository extends JpaRepository<CdmData, Long> {
    Optional<CdmData> findByCdmId(String cdmId);
    boolean existsByCdmId(String cdmId);

    @Query("SELECT c FROM CdmData c " +
            "WHERE c.noradId1 = :noradId1 " +
            "AND c.tca BETWEEN :from AND :to " +
            "ORDER BY c.pc DESC NULLS LAST")
    List<CdmData> findByPrimaryAndTcaWindow(
            @Param("noradId1") Integer noradId1,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query(value = "SELECT * FROM cdm_data c " +
            "WHERE c.norad_id1 = :noradId1 " +
            "AND c.norad_id2 = :noradId2 " +
            "AND c.tca BETWEEN :from AND :to " +
            "ORDER BY ABS(EXTRACT(EPOCH FROM (c.tca - :tca))) ASC",
            nativeQuery = true)
    List<CdmData> findBestMatchForPair(
            @Param("noradId1") Integer noradId1,
            @Param("noradId2") Integer noradId2,
            @Param("tca") LocalDateTime tca,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query("SELECT c FROM CdmData c " +
            "WHERE c.pc >= :minPc " +
            "AND c.tca > :now " +
            "ORDER BY c.pc DESC")
    List<CdmData> findHighPcCdms(
            @Param("minPc") Double minPc,
            @Param("now") LocalDateTime now
    );

    @Query("DELETE FROM CdmData c WHERE c.tca < :cutoff")
    void deleteOldCdms(@Param("cutoff") LocalDateTime cutoff);

    List<CdmData> findByNoradId1AndTcaAfter(Integer noradId1, LocalDateTime after);

    List<CdmData> findByNoradId2AndTcaAfter(Integer noradId2, LocalDateTime after);
}