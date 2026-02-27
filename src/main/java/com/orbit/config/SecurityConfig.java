package com.orbit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${api.security.admin.username:admin}")
    private String adminUsername;

    @Value("${api.security.admin.password:changeme}")
    private String adminPassword;

    @Value("${api.security.operator.username:operator}")
    private String operatorUsername;

    @Value("${api.security.operator.password:changeme}")
    private String operatorPassword;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()

                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST,   "/api/**"
                        ).hasRole("ADMIN")
                        .requestMatchers(
                                org.springframework.http.HttpMethod.DELETE, "/api/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                org.springframework.http.HttpMethod.GET,    "/api/**"
                        ).hasAnyRole("ADMIN", "OPERATOR")

                        .anyRequest().authenticated()
                )
                .httpBasic(basic -> basic.realmName("Orbit Conjunction System"));
        return http.build();
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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}