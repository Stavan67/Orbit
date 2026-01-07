package com.orbit.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "tle_data")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TleData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tle_id")
    private Long tleId;

    @Column(name = "satellite_id", nullable = false)
    private Long satelliteId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "satellite_id", insertable = false, updatable = false)
    private Satellite satellite;

    @Column(name = "line1", nullable = false, length = 69)
    private String line1;

    @Column(name = "line2", nullable = false, length = 69)
    private String line2;

    @Column(name = "epoch", nullable = false)
    private LocalDateTime epoch;

    @Column(name = "mean_motion")
    private Double meanMotion;

    @Column(name = "eccentricity")
    private Double eccentricity;

    @Column(name = "inclination")
    private Double inclination;

    @Column(name = "raan")
    private Double raan;

    @Column(name = "argument_of_perigee")
    private Double argumentOfPerigee;

    @Column(name = "mean_anomaly")
    private Double meanAnomaly;

    @Column(name = "classification", length = 1)
    private String classification;

    @Column(name = "element_set_number")
    private Integer elementSetNumber;

    @Column(name = "is_current")
    private Boolean isCurrent;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if(isCurrent == null){
            isCurrent = true;
        }
    }
}
