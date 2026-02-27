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

    /**
     * Returns true if this object has any portion of its orbit within LEO altitude band.
     *
     * FIX: The previous version required perigee <= 2000 km, which incorrectly excluded
     * objects whose perigee is in upper LEO but whose apogee is also in LEO — valid LEO
     * objects with slightly eccentric orbits. The correct check is:
     *   - perigee must be above the atmosphere (>= 160 km) so the object hasn't deorbited
     *   - the orbit must pass through the LEO band, i.e. perigee <= 2000 km
     *     (if perigee > 2000 km the entire orbit is above LEO, so no LEO conjunction possible)
     *
     * Note: there is no upper bound needed on apogee here — an object with perigee
     * at 300 km and apogee at 5000 km still passes through LEO on every orbit.
     */
    public boolean passesThroughLeo() {
        double perigee = getPerigeeAltitude();
        return perigee >= 160.0 && perigee <= 2000.0;
    }

    /**
     * Determines whether this satellite's orbit can geometrically produce a conjunction
     * with {@code other}, given altitude and inclination tolerance parameters.
     *
     * <p><b>Altitude check</b>: The expanded altitude bands of both satellites must overlap.
     * This is the most physically meaningful coarse filter.</p>
     *
     * <p><b>Inclination check</b>: We compare <em>effective inclinations</em> rather than
     * raw inclination values. The effective inclination {@code inclEff = min(i, 180 - i)}
     * represents the maximum latitude a satellite reaches on its ground track:
     * <ul>
     *   <li>A prograde 40° orbit reaches ±40° latitude → inclEff = 40°</li>
     *   <li>A retrograde 97° orbit reaches ±83° latitude → inclEff = 83°</li>
     *   <li>A retrograde 140° orbit reaches ±40° latitude → inclEff = 40°</li>
     * </ul>
     * Two orbits share a common latitude band (and thus can geometrically conjunct)
     * if their effective inclination ranges overlap. Since both ranges are symmetric
     * about the equator [-inclEff, +inclEff], they always overlap as long as both
     * inclEff > 0. The tolerance check {@code |inclEff1 - inclEff2| <= tolerance}
     * therefore acts as a <em>computational heuristic</em> to exclude pairs whose
     * latitude coverage is so different that conjunction is geometrically rare —
     * not physically impossible.
     *
     * <p>A tolerance of 60° is recommended for LEO conjunction screening to avoid
     * missing real events like SSO debris vs. lower-inclination objects.</p>
     */
    public boolean canConjuctWith(
            OrbitalElements other,
            double altitudeTolerance,
            double inclinationTolerance
    ) {
        // --- Inclination check (using effective latitude-coverage inclination) ---
        // effective inclination = max latitude the satellite reaches
        double myInclEff    = Math.min(this.inclination,  180.0 - this.inclination);
        double otherInclEff = Math.min(other.inclination, 180.0 - other.inclination);
        double inclinationDiff = Math.abs(myInclEff - otherInclEff);
        if (inclinationDiff > inclinationTolerance) {
            return false;
        }

        // --- Altitude overlap check ---
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