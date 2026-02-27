package com.orbit.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "cdm_data", indexes = {
        @Index(name = "idx_cdm_norad1_tca", columnList = "norad_id_1, tca"),
        @Index(name = "idx_cdm_norad2", columnList = "norad_id_2"),
        @Index(name = "idx_cdm_pc", columnList = "pc"),
        @Index(name = "idx_cdm_created", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CdmData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "cdm_id", unique = true, nullable = false)
    private String cdmId;

    @Column(name = "cdm_created")
    private LocalDateTime cdmCreated;

    @Column(name = "norad_id_1", nullable = false)
    private Integer noradId1;

    @Column(name = "norad_id_2", nullable = false)
    private Integer noradId2;

    @Column(name = "sat1_name", length = 100)
    private String sat1Name;

    @Column(name = "sat2_name", length = 100)
    private String sat2Name;

    @Column(name = "tca", nullable = false)
    private LocalDateTime tca;

    @Column(name = "miss_distance_m")
    private Double missDistanceM;

    @Column(name = "relative_speed_ms")
    private Double relativeSpeedMs;

    @Column(name = "pc")
    private Double pc;

    @Column(name = "emergency_reportable", length = 1)
    private String emergencyReportable;

    @Column(name = "sat1_type", length = 50)
    private String sat1Type;

    @Column(name = "sat2_type", length = 50)
    private String sat2Type;

    @Column(name = "sat1_rcs", length = 20)
    private String sat1Rcs;

    @Column(name = "sat2_rcs", length = 20)
    private String sat2Rcs;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conjunction_event_id")
    private ConjunctionEvent conjunctionEvent;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}