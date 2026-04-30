package com.fixy.backend.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .httpBasic(Customizer.withDefaults())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/", "/index.html", "/styles.css", "/script.js", "/ops.css").permitAll()
            .requestMatchers("/api/health", "/api/intake").permitAll()
            .requestMatchers("/api/public/**").permitAll()
            .requestMatchers("/ops.html", "/api/leads/**", "/api/providers/**").authenticated()
            .anyRequest().permitAll());

    return http.build();
  }

  @Bean
  UserDetailsManager userDetailsService(
      @Value("${fixy.security.username}") String username,
      @Value("${fixy.security.password}") String password,
      PasswordEncoder passwordEncoder
  ) {
    UserDetails opsUser = User.withUsername(username)
        .password(passwordEncoder.encode(password))
        .roles("OPS")
        .build();

    return new InMemoryUserDetailsManager(opsUser);
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of(
        "https://www.fixy.com.uy",
        "https://fixy.com.uy",
        "https://cyl-castillo.github.io",
        "http://127.0.0.1:5173",
        "http://localhost:5173",
        "http://127.0.0.1:8080",
        "http://localhost:8080"
    ));
    configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(false);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
