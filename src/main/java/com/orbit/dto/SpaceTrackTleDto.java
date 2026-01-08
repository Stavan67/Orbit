package com.orbit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SpaceTrackTleDto {
    @JsonProperty("NORAD_CAT_ID")
    private Integer noradCatId;

    @JsonProperty("OBJECT_NAME")
    private String objectName;

    @JsonProperty("OBJECT_TYPE")
    private String objectType;

    @JsonProperty("CLASSIFICATION_TYPE")
    private String classificationType;

    @JsonProperty("INTLDES")
    private String intldes;

    @JsonProperty("EPOCH")
    private String epoch;

    @JsonProperty("MEAN_MOTION")
    private Double meanMotion;

    @JsonProperty("ECCENTRICITY")
    private Double eccentricity;

    @JsonProperty("INCLINATION")
    private Double inclination;

    @JsonProperty("RA_OF_ASC_NODE")
    private Double raOfAscNode;

    @JsonProperty("ARG_OF_PERICENTER")
    private Double argOfPericenter;

    @JsonProperty("MEAN_ANOMALY")
    private Double meanAnomaly;

    @JsonProperty("ELEMENT_SET_NO")
    private Integer elementSetNo;

    @JsonProperty("TLE_LINE1")
    private String tleLine1;

    @JsonProperty("TLE_LINE2")
    private String tleLine2;

    @JsonProperty("COUNTRY_CODE")
    private String countryCode;
}
