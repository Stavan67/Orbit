package com.orbit.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "collision_alerts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollisionAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alert_id")
    private Long alertId;

    @Column(name = "satellite1_id", nullable = false)
    private Long satellite1Id;

    @Column(name = "satellite2_id", nullable = false)
    private Long satellite2Id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "satellite1_id", insertable = false, updatable = false)
    private Satellite satellite1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "satellite2_id", insertable = false, updatable = false)
    private Satellite satellite2;

    @Column(name = "tca", nullable = false)
    private LocalDateTime tca; // Time of Closest Approach

    @Column(name = "min_distance", nullable = false)
    private Double minDistance; // in kilometers

    @Column(name = "relative_velocity")
    private Double relativeVelocity; // in km/s

    @Column(name = "collision_probability")
    private Double collisionProbability;

    @Column(name = "alert_level", length = 20)
    private String alertLevel; // CRITICAL, HIGH, MEDIUM, LOW

    @Column(name = "is_resolved")
    private Boolean isResolved;

    @Column(name = "satellite1_position_x")
    private Double satellite1PositionX;

    @Column(name = "satellite1_position_y")
    private Double satellite1PositionY;

    @Column(name = "satellite1_position_z")
    private Double satellite1PositionZ;

    @Column(name = "satellite2_position_x")
    private Double satellite2PositionX;

    @Column(name = "satellite2_position_y")
    private Double satellite2PositionY;

    @Column(name = "satellite2_position_z")
    private Double satellite2PositionZ;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isResolved == null) {
            isResolved = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}