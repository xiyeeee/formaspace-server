package com.xiye.formaspace.config;

import com.xiye.formaspace.auth.security.ActiveUserAuthenticationConverter;
import com.xiye.formaspace.auth.security.SecurityJsonResponse;
import com.xiye.formaspace.common.exception.ErrorCode;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import com.xiye.formaspace.auth.security.AuthenticationStorageException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.*;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        String algorithm = "pbkdf2@SpringSecurity_v5_8";
        return new DelegatingPasswordEncoder(algorithm,
                Map.of(algorithm, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityJsonResponse json,
            ActiveUserAuthenticationConverter activeUser) throws Exception {
        var entryPoint = (org.springframework.security.web.AuthenticationEntryPoint) (request, response, error) ->
                json.write(response, error instanceof AuthenticationStorageException
                        ? ErrorCode.SERVICE_UNAVAILABLE : ErrorCode.UNAUTHORIZED);
        var denied = (org.springframework.security.web.access.AccessDeniedHandler) (request, response, error) ->
                json.write(response, ErrorCode.FORBIDDEN);
        var bearer = new DefaultBearerTokenResolver();
        bearer.setAllowFormEncodedBodyParameter(false);
        bearer.setAllowUriQueryParameter(false);
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/email-codes", "/api/auth/register", "/api/auth/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .oauth2ResourceServer(resource -> resource.bearerTokenResolver(bearer)
                        .authenticationEntryPoint(entryPoint).accessDeniedHandler(denied)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(activeUser)));
        return http.build();
    }
}
