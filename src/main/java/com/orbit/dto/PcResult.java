package com.orbit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PcResult {
    private double pc;

    private double sigmaXm;

    private double sigmaYm;

    private double combinedHbrM;

    private String method;

    private int primaryTleAgeDays;

    private int secondaryTleAgeDays;

    public String getInterpretation() {
        if (pc >= 0.01)       return "CRITICAL — Maneuver almost certainly required";
        if (pc >= 0.001)      return "HIGH — Maneuver strongly recommended";
        if (pc >= 0.0001)     return "MEDIUM — Monitor closely, prepare contingency";
        if (pc >= 0.00001)    return "LOW — Routine monitoring";
        return "NEGLIGIBLE — No action required";
    }

    public String getPcFormatted() {
        return String.format("%.3e", pc);
    }
}