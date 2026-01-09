package com.orbit.service;

import com.orbit.entity.TleData;
import lombok.extern.slf4j.Slf4j;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.util.FastMath;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@Slf4j
public class OrbitalPropagationService {
    public double[] calculatePosition(TleData tleData, LocalDateTime targetTime){
        try{
            TLE tle = new TLE(tleData.getLine1(), tleData.getLine2());
            TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);
            AbsoluteDate date = toAbsoluteDate(targetTime);

            PVCoordinates pv = propagator.getPVCoordinates(date, FramesFactory.getTEME());

            Vector3D position = pv.getPosition();

            return new double[]{
                    position.getX() / 1000.0,
                    position.getY() / 1000.0,
                    position.getZ() / 1000.0,
            };
        } catch (Exception e) {
            log.error("Error calculating position for satellite", e);
            throw new RuntimeException("Position calculation failed", e);
        }
    }

    public double[] calculatePositionAndVelocity(TleData tleData, LocalDateTime targetTime){
        try{
            TLE tle = new TLE(tleData.getLine1(), tleData.getLine2());
            TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);
            AbsoluteDate date = toAbsoluteDate(targetTime);

            PVCoordinates pv = propagator.getPVCoordinates(date, FramesFactory.getTEME());

            Vector3D position = pv.getPosition();
            Vector3D velocity = pv.getVelocity();

            return new double[]{
                    position.getX() / 1000.0,
                    position.getY() / 1000.0,
                    position.getZ() / 1000.0,
                    velocity.getX() / 1000.0,
                    velocity.getY() / 1000.0,
                    velocity.getZ() / 1000.0
            };
        } catch (Exception e) {
            log.error("Error calculating position and velocity", e);
            throw new RuntimeException("Position/velocity calculation failed", e);
        }
    }

    public double calculateDistance(TleData tle1,  TleData tle2, LocalDateTime targetTime){
        double[] pos1 = calculatePosition(tle1, targetTime);
        double[] pos2 = calculatePosition(tle2, targetTime);
        return calculateEuclideanDistance(pos1, pos2);
    }

    public double calculateEuclideanDistance(double[] pos1, double[] pos2){
        double dx = pos1[0] - pos2[0];
        double dy = pos1[1] - pos2[1];
        double dz = pos1[2] - pos2[2];

        return FastMath.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double calculateRelativeVelocity(double[] pv1, double[] pv2){
        double dvx = pv1[3] - pv2[3];
        double dvy = pv1[4] - pv2[4];
        double dvz = pv1[5] - pv2[5];
        return FastMath.sqrt(dvx * dvx + dvy * dvy + dvz * dvz);
    }

    public double[] findClosestApproach(TleData tle1, TleData tle2, LocalDateTime searchStart, LocalDateTime searchEnd, int stepMinutes){
        LocalDateTime currentTime = searchStart;
        double minDistance = Double.MAX_VALUE;
        LocalDateTime tcaTime = searchStart;

        while(currentTime.isBefore(searchEnd) || currentTime.isEqual(searchEnd)){
            double distance = calculateDistance(tle1, tle2, currentTime);
            if(distance < minDistance){
                minDistance = distance;
                tcaTime = currentTime;
            }
            currentTime = currentTime.plusMinutes(stepMinutes);
        }
        LocalDateTime refineStart = tcaTime.minusMinutes(stepMinutes);
        LocalDateTime refineEnd = tcaTime.plusMinutes(stepMinutes);
        currentTime = refineStart;

        while(currentTime.isBefore(refineEnd) || currentTime.isEqual(refineEnd)){
            double distance = calculateDistance(tle1, tle2, currentTime);
            if(distance < minDistance){
                minDistance = distance;
                tcaTime = currentTime;
            }
            currentTime = currentTime.plusMinutes(30);
        }
        return new double[]{
                tcaTime.toEpochSecond(ZoneOffset.UTC),
                minDistance
        };
    }

    private AbsoluteDate toAbsoluteDate(LocalDateTime localDateTime){
        return new AbsoluteDate(
                localDateTime.getYear(),
                localDateTime.getMonthValue(),
                localDateTime.getDayOfMonth(),
                localDateTime.getHour(),
                localDateTime.getMinute(),
                localDateTime.getSecond() + localDateTime.getNano() / 1e9,
                TimeScalesFactory.getUTC()
        );
    }
}
