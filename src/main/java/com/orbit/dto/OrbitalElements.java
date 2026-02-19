package com.orbit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrbitalElements {
    private Integer noradId;
    private String name;
    private double semiMajorAxis; // km
    private double eccentricity;
    private double inclination; // degrees
    private double raan; // Right Ascension of Ascending Node (degrees)
    private double meanMotion; // revolutions per day
    private double altitude; // km

    public boolean isLeo() {
        return altitude >= 160.0 && altitude <= 2000.0;
    }

    public boolean canConjuctWith(
            OrbitalElements other,
            double altitudeTolerance,
            double inclinationTolerance
    ) {
        double altitudeDiff = Math.abs(this.altitude - other.altitude);
        if (altitudeDiff > altitudeTolerance) {
            return false;
        }

        double inclinationDiff = Math.abs(this.inclination - other.inclination);
        if (inclinationDiff > inclinationTolerance) {
            return false;
        }

        return true;
    }

    public double getRaanDifference(OrbitalElements other) {
        double diff = Math.abs(this.raan - other.raan);
        if (diff > 180.0) {
            diff = 360.0 - diff;
        }
        return diff;
    }

    public boolean hasCompatibleRaan(OrbitalElements other, double raanTolerance) {
        return getRaanDifference(other) <= raanTolerance;
    }
}