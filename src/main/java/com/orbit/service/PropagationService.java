package com.orbit.service;

import com.orbit.entity.TleData;
import com.orbit.exception.CorruptTleException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PropagationService {
    public static final double LEO_MAX_SPEED_MS = 11_000.0;

    private final Set<Integer> corruptPropagatorIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Getter
    private final Frame frame;
    private final TimeScale utc;

    @Getter
    @Value("${tle.max-age-days:30}")
    private int maxTleAgeDays;

    public PropagationService() {
        this.frame = FramesFactory.getTEME();
        this.utc = TimeScalesFactory.getUTC();
    }

    public TLE createTLE(TleData tleData) {
        try {
            LocalDateTime epoch = tleData.getEpoch();
            if (epoch != null) {
                long ageDays = ChronoUnit.DAYS.between(epoch, LocalDateTime.now(ZoneOffset.UTC));
                if (ageDays > maxTleAgeDays) {
                    log.warn("TLE for NORAD {} is {} days old (epoch {}). "
                                    + "SGP4 accuracy is significantly degraded beyond {} days.",
                            tleData.getSatellite().getNoradId(), ageDays, epoch, maxTleAgeDays);
                } else if (ageDays > 7) {
                    log.debug("TLE for NORAD {} is {} days old (epoch {}) — accuracy may be reduced.",
                            tleData.getSatellite().getNoradId(), ageDays, epoch);
                }
            } else {
                log.warn("TLE for NORAD {} has a null epoch — cannot validate staleness.",
                        tleData.getSatellite().getNoradId());
            }
            return new TLE(tleData.getLine1(), tleData.getLine2());
        } catch (Exception e) {
            log.error("Failed to create TLE for NORAD {}: {}",
                    tleData.getSatellite().getNoradId(), e.getMessage());
            throw new RuntimeException("Invalid TLE data", e);
        }
    }

    public TLEPropagator createPropagator(TLE tle) {
        return TLEPropagator.selectExtrapolator(tle);
    }

    public AbsoluteDate toAbsoluteDate(LocalDateTime localDateTime) {
        java.util.Date date = java.util.Date.from(
                localDateTime.toInstant(ZoneOffset.UTC)
        );
        return new AbsoluteDate(date, utc);
    }

    public LocalDateTime toLocalDateTime(AbsoluteDate absoluteDate) {
        java.util.Date date = absoluteDate.toDate(utc);
        return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }

    public Vector3D propagateToPosition(TLEPropagator propagator, AbsoluteDate date) {
        try {
            PVCoordinates pv = propagator.propagate(date).getPVCoordinates(frame);
            return pv.getPosition();
        } catch (Exception e) {
            log.error("Propagation to position failed at {}: {}", date, e.getMessage());
            throw new RuntimeException("Propagation error", e);
        }
    }

    public PVCoordinates propagateToPV(TLEPropagator propagator, AbsoluteDate date) {
        int propagatorId = System.identityHashCode(propagator);

        if (corruptPropagatorIds.contains(propagatorId)) {
            throw new CorruptTleException(-1, Double.NaN, LEO_MAX_SPEED_MS);
        }

        try {
            PVCoordinates pv = propagator.propagate(date).getPVCoordinates(frame);
            double speed = pv.getVelocity().getNorm();

            if (speed > LEO_MAX_SPEED_MS) {
                corruptPropagatorIds.add(propagatorId);
                log.debug("Corrupt TLE detected: speed {} m/s exceeds {} m/s threshold. Skipping pair.",
                        String.format("%.0f", speed),
                        String.format("%.0f", LEO_MAX_SPEED_MS));
                throw new CorruptTleException(-1, speed, LEO_MAX_SPEED_MS);
            }

            return pv;

        } catch (CorruptTleException e) {
            throw e;
        } catch (Exception e) {
            log.error("Propagation to PV failed at {}: {}", date, e.getMessage());
            throw new RuntimeException("Propagation error", e);
        }
    }

    public double calculateDistance(Vector3D pos1, Vector3D pos2) {
        return Vector3D.distance(pos1, pos2);
    }

    public double calculateRelativeVelocity(Vector3D vel1, Vector3D vel2) {
        Vector3D relativeVel = vel1.subtract(vel2);
        return relativeVel.getNorm();
    }
}