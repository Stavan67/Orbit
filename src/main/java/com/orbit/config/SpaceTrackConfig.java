package com.orbit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "spacetrack")
@Data
public class SpaceTrackConfig {
    private String username;
    private String password;
    private String baseUrl = "https://www.space-track.org";
}
