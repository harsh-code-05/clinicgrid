package com.harsh.hospitalmanagement.config;

import com.harsh.hospitalmanagement.service.CustomOidcUserService;
import com.harsh.hospitalmanagement.service.CustomUserDetailsService;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.security.LoginRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(
            CustomUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {

        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(userDetailsService);

        provider.setPasswordEncoder(passwordEncoder);

        return provider;
    }
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    public SessionAuthenticationStrategy passwordSessionStrategy(CsrfTokenRepository csrfTokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokens)));
    }

    @Bean
    public LoginRateLimiter loginRateLimiter() {
        return new LoginRateLimiter();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration) throws Exception {

        return configuration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.frontend.url:http://localhost:5173/}") String frontendUrl,
            @Value("${app.cors.allowed-origins:}") String additionalOrigins,
            @Value("${app.cors.allow-localhost:true}") boolean allowLocalhost) {

        CorsConfiguration configuration = new CorsConfiguration();
        Set<String> origins = new LinkedHashSet<>();

        String normalizedFrontend = frontendUrl != null ? frontendUrl.replaceAll("/+$", "").trim() : "";
        if (!normalizedFrontend.isEmpty()) {
            origins.add(normalizedFrontend);
        }
        if (allowLocalhost) {
            origins.add("http://localhost:5173");
            origins.add("http://127.0.0.1:5173");
        }

        if (additionalOrigins != null && !additionalOrigins.isBlank()) {
            for (String origin : additionalOrigins.split(",")) {
                String trimmed = origin.replaceAll("/+$", "").trim();
                if (!trimmed.isEmpty() && !"*".equals(trimmed)) {
                    origins.add(trimmed);
                }
            }
        }

        configuration.setAllowedOrigins(new ArrayList<>(origins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-CSRF-TOKEN",
                "Accept",
                "Origin",
                "X-Requested-With"
        ));
        configuration.setExposedHeaders(List.of(
                "X-CSRF-TOKEN",
                "Content-Disposition"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokens,
            CustomOidcUserService customOidcUserService,
            CorsConfigurationSource corsConfigurationSource,
            ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider,
            @Value("${app.frontend.url:http://localhost:5173/}") String frontendUrl)
            throws Exception {

        ClientRegistrationRepository clientRegistrationRepository = clientRegistrationRepositoryProvider.getIfAvailable();
        String targetUrl = frontendUrl.endsWith("/") ? frontendUrl : frontendUrl + "/";
        var apiRequests = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        var browserLogin = new LoginUrlAuthenticationEntryPoint(targetUrl + "login");
        var browserAccessDenied = new AccessDeniedHandlerImpl();
        var browserRequestCache = new HttpSessionRequestCache();
        browserRequestCache.setRequestMatcher(request ->
                HttpMethod.GET.matches(request.getMethod()) && !apiRequests.matches(request));

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .requestCache(cache -> cache.requestCache(browserRequestCache))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> {
                    if (apiRequests.matches(request)) {
                        response.setStatus(HttpStatus.UNAUTHORIZED.value());
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        SensitiveResponseHeaders.apply(response);
                        response.getWriter().write("{\"message\":\"Not authenticated\"}");
                    } else {
                        browserLogin.commence(request, response, failure);
                    }
                }).accessDeniedHandler((request, response, failure) -> {
                    if (apiRequests.matches(request)) {
                        // sendError would redispatch to the protected /error route and redirect anonymous API callers.
                        response.setStatus(HttpStatus.FORBIDDEN.value());
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        SensitiveResponseHeaders.apply(response);
                        response.getWriter().write("{\"status\":403,\"message\":\"Access denied\"}");
                    } else {
                        browserAccessDenied.handle(request, response, failure);
                    }
                }))
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/ws/**")
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(headerOnlyMultipartCsrfHandler()))
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/auth/logout"))
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                        // Invalidate sessions from before local-ID principals were introduced.
                        if (authentication != null && authentication.isAuthenticated()
                                && !(authentication.getPrincipal() instanceof LocalAccount.Principal)) {
                            SecurityContextHolder.clearContext();
                            var session = request.getSession(false);
                            if (session != null) {
                                session.invalidate();
                            }
                        }
                        chain.doFilter(request, response);
                    }
                }, AnonymousAuthenticationFilter.class)

                .securityContext(securityContext ->
                        securityContext
                                .securityContextRepository(
                                        securityContextRepository));

        if (clientRegistrationRepository != null) {
            http.oauth2Login(oauth2 ->
                    oauth2
                            .clientRegistrationRepository(clientRegistrationRepository)
                            .loginPage(targetUrl + "login")
                            .userInfoEndpoint(userInfo ->
                                    userInfo
                                            .oidcUserService(
                                                    customOidcUserService))
                            .defaultSuccessUrl(targetUrl, true)
                            .failureUrl(targetUrl + "login?error=google"));
        }

        http.authorizeHttpRequests(auth -> auth

                        .requestMatchers(
                                "/oauth2/**",
                                "/login/**"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/users",
                                "/api/patients/register",
                                "/api/doctors/register"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/register/**"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/me", "/api/auth/csrf"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/health"
                        ).permitAll()

                        .requestMatchers("/logout", "/api/auth/logout").denyAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/specializations"
                        ).permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/v2/discovery/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v2/discovery/locations/resolve",
                                "/api/v2/discovery/locations/nearby").permitAll()

                        // Doctor application: public registration (unauthenticated)
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctor-applications/register"
                        ).permitAll()

                        // Doctor application: admin-only review endpoints
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/doctor-applications/pending"
                        ).hasRole("ADMIN")

                        // Doctor application: authenticated applicant routes (must be before /* wildcard)
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/doctor-applications/me"
                        ).authenticated()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/doctor-applications/*"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/doctor-applications/*/approve"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/doctor-applications/*/reject"
                        ).hasRole("ADMIN")

                        // Doctor application: authenticated applicant POST apply
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctor-applications/apply"
                        ).authenticated()

                        .requestMatchers(
                                "/api/admin/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/specializations/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                "/api/doctors/*/locations",
                                "/api/doctors/*/locations/*",
                                "/api/doctors/*/video-fee"
                        ).hasAnyRole("DOCTOR", "ADMIN")

                        .requestMatchers("/api/doctors/*/verification").hasAnyRole("DOCTOR", "ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctors/*/verification/credentials",
                                "/api/doctors/*/verification/submit"
                        ).hasRole("DOCTOR")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctors/*/verification/review",
                                "/api/doctors/*/verification/approve",
                                "/api/doctors/*/verification/reject"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctors/*/verification/documents/*"
                        ).hasRole("DOCTOR")

                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/doctors/*/verification/documents/*"
                        ).hasRole("DOCTOR")

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/doctors/*/verification/documents/*"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/doctors/*/verification/documents",
                                "/api/doctors/*/verification/documents/*/history"
                        ).hasAnyRole("DOCTOR", "ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctors/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/doctors/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/patients/me"
                        ).hasRole("PATIENT")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/patients/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctor-availability/**"
                        ).hasAnyRole("DOCTOR", "ADMIN")

                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/doctor-availability/**"
                        ).hasAnyRole("DOCTOR", "ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/appointment-slots/**"
                        ).hasAnyRole("DOCTOR", "ADMIN")

                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/appointment-slots/*/unblock"
                        ).hasRole("ADMIN")

                        .requestMatchers(HttpMethod.PATCH, "/api/appointments/*/cancel")
                        .hasAnyRole("PATIENT", "DOCTOR", "ADMIN")

                        .requestMatchers(HttpMethod.PATCH, "/api/appointments/*/complete")
                        .hasAnyRole("DOCTOR", "ADMIN")


                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/appointments/**", "/api/v2/appointments"
                        ).hasRole("PATIENT")

                        .requestMatchers(HttpMethod.GET, "/api/appointments/*/video-room")
                        .hasAnyRole("DOCTOR", "PATIENT")

                        .requestMatchers("/ws/**").authenticated()

                        .requestMatchers("/api/patient/assistant/**").hasRole("PATIENT")
                        .requestMatchers("/api/patient/bills/**").hasRole("PATIENT")

                        .requestMatchers("/api/**").authenticated()

                        .anyRequest().authenticated()
                );

        return http.build();
    }

    private static CsrfTokenRequestHandler headerOnlyMultipartCsrfHandler() {
        var delegate = new XorCsrfTokenRequestAttributeHandler();
        return new CsrfTokenRequestHandler() {
            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
                delegate.handle(request, response, csrfToken);
            }

            @Override
            public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
                if (isMultipartRequest(request)) {
                    // Reject parameter-only CSRF tokens on multipart uploads so Tomcat never calls parseParts() inside CsrfFilter.
                    return delegate.resolveCsrfTokenValue(new HeaderOnlyCsrfRequestWrapper(request), csrfToken);
                }
                return delegate.resolveCsrfTokenValue(request, csrfToken);
            }
        };
    }

    private static boolean isMultipartRequest(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.stripLeading().toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    private static final class HeaderOnlyCsrfRequestWrapper extends HttpServletRequestWrapper {

        private HeaderOnlyCsrfRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getParameter(String name) {
            return null;
        }

        @Override
        public String[] getParameterValues(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.emptyEnumeration();
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return Collections.emptyMap();
        }
    }
}
