package com.orbit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
public class UserDetailsServiceConfig {

    @Value("${api.security.admin.username}")
    private String adminUsername;

    @Value("${api.security.admin.password}")
    private String adminPassword;

    @Value("${api.security.operator.username}")
    private String operatorUsername;

    @Value("${api.security.operator.password}")
    private String operatorPassword;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername(adminUsername)
                        .password(encoder.encode(adminPassword))
                        .roles("ADMIN", "OPERATOR")
                        .build(),
                User.withUsername(operatorUsername)
                        .password(encoder.encode(operatorPassword))
                        .roles("OPERATOR")
                        .build()
        );
    }
}