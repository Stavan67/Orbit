package com.orbit.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "satellites")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Satellite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "satellite_id")
    private Long satelliteId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "norad_id", nullable = false, unique = true)
    private Integer noradId;

    @Column(name = "international_designator", length = 20)
    private String internationalDesignator;

    @Column(name = "object_type", length = 50)
    private String objectType;

    @Column(name = "country", length = 50)
    private String country;

    @Column(name = "launch_date")
    private LocalDateTime launchDate;

    @Column(name = "is_active")
    private Boolean isActive;

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
