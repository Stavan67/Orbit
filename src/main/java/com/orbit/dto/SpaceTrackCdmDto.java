package com.orbit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SpaceTrackCdmDto {
    @JsonProperty("CDM_ID")
    private String cdmId;

    @JsonProperty("CREATED")
    private String created;

    @JsonProperty("SAT_1_ID")
    private Integer sat1Id;

    @JsonProperty("SAT_2_ID")
    private Integer sat2Id;

    @JsonProperty("SAT1_NAME")
    private String sat1Name;

    @JsonProperty("SAT2_NAME")
    private String sat2Name;

    @JsonProperty("TCA")
    private String tca;

    @JsonProperty("MIN_RNG")
    private Double minRng;

    @JsonProperty("REL_SPEED")
    private Double relSpeed;

    @JsonProperty("PC")
    private Double pc;

    @JsonProperty("EMERGENCY_REPORTABLE")
    private String emergencyReportable;

    @JsonProperty("SAT1_TYPE")
    private String sat1Type;

    @JsonProperty("SAT2_TYPE")
    private String sat2Type;

    @JsonProperty("SAT1_RCS")
    private String sat1Rcs;

    @JsonProperty("SAT2_RCS")
    private String sat2Rcs;
}