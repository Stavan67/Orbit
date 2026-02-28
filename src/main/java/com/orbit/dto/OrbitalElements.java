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
    private double inclination;   // degrees  (0–180)
    private double raan;          // degrees
    private double meanMotion;    // revolutions per day
    private double altitude;      // mean altitude, km
    private static final double EARTH_RADIUS_KM = 6378.137;

    public double getPerigeeAltitude() {
        return semiMajorAxis * (1.0 - eccentricity) - EARTH_RADIUS_KM;
    }

    public double getApogeeAltitude() {
        return semiMajorAxis * (1.0 + eccentricity) - EARTH_RADIUS_KM;
    }

    public boolean isLeo() {
        return getPerigeeAltitude() >= 160.0 && getApogeeAltitude() <= 2000.0;
    }

    public boolean passesThroughLeo() {
        double perigee = getPerigeeAltitude();
        return perigee >= 160.0 && perigee <= 2000.0;
    }

    public boolean canConjuctWith(
            OrbitalElements other,
            double altitudeTolerance,
            double inclinationTolerance
    ) {
        double myInclEff    = Math.min(this.inclination,  180.0 - this.inclination);
        double otherInclEff = Math.min(other.inclination, 180.0 - other.inclination);
        double inclinationDiff = Math.abs(myInclEff - otherInclEff);
        if (inclinationDiff > inclinationTolerance) {
            return false;
        }

        double thisPerigee  = this.getPerigeeAltitude()   - altitudeTolerance;
        double thisApogee   = this.getApogeeAltitude()    + altitudeTolerance;
        double otherPerigee = other.getPerigeeAltitude()  - altitudeTolerance;
        double otherApogee  = other.getApogeeAltitude()   + altitudeTolerance;

        boolean noOverlap = thisApogee < otherPerigee || otherApogee < thisPerigee;
        return !noOverlap;
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

    /**
     * Returns the effective inclination — i.e. the maximum latitude (in degrees)
     * that this satellite's ground track reaches. For prograde orbits this equals
     * the inclination directly; for retrograde orbits it is (180° - inclination).
     */
    public double getEffectiveInclination() {
        return Math.min(inclination, 180.0 - inclination);
    }
}