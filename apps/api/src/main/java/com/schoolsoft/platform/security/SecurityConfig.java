package com.schoolsoft.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The filter chain decides <em>who</em> may reach the API; {@code @PreAuthorize}
 * on every controller method decides <em>what</em> they may do once inside.
 *
 * <p>{@code @EnableMethodSecurity} is what makes the second half real — without
 * it every annotation on every controller is decoration, and the
 * {@code .anyRequest().authenticated()} below is the whole of the
 * authorization model: any valid token, any endpoint. {@code RbacArchitectureTest}
 * guards the annotations; nothing but this line guards the annotations
 * being enforced at all.</p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final TenantResolverFilter tenantFilter;

    public SecurityConfig(TenantResolverFilter tenantFilter) { this.tenantFilter = tenantFilter; }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var cors = new CorsConfiguration();
        cors.addAllowedOriginPattern("*");
        cors.addAllowedHeader("*");
        cors.addAllowedMethod("*");
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);

        http
            .cors(c -> c.configurationSource(source))
            .csrf(c -> c.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(
                    "/v1/auth/**",
                    "/v1/public/**",
                    "/v1/webhooks/**",
                    "/actuator/health",
                    "/actuator/info"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
