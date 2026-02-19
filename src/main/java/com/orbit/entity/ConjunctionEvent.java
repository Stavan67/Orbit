package com.orbit.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "conjunction_events", indexes = {
        @Index(name = "idx_primary_tca", columnList = "primary_satellite_id,tca"),
        @Index(name = "idx_risk_level", columnList = "risk_level"),
        @Index(name = "idx_tca", columnList = "tca")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConjunctionEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_satellite_id", nullable = false)
    private Satellite primarySatellite;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "secondary_satellite_id", nullable = false)
    private Satellite secondarySatellite;

    @Column(name = "tca", nullable = false)
    private LocalDateTime tca;

    @Column(name = "miss_distance", nullable = false)
    private Double missDistance;

    @Column(name = "relative_velocity", nullable = false)
    private Double relativeVelocity;

    @Column(name = "risk_level", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private RiskLevel riskLevel;

    @Column(name = "primary_altitude")
    private Double primaryAltitude;

    @Column(name = "secondary_altitude")
    private Double secondaryAltitude;

    @Column(name = "screening_epoch")
    private LocalDateTime screeningEpoch;

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

    public enum RiskLevel {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }
}