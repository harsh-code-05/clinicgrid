package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.security.WithMvcSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HealthCheckController.class)
@WithMvcSecurity
class HealthCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getHealthReturnsHttp200AndStatusUpWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/health")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    void postHealthWithoutAuthenticationOrCsrfIsBlocked() throws Exception {
        // Only GET /api/health is permitted unauthenticated; POST is blocked
        mockMvc.perform(post("/api/health"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void protectedApiEndpointsRemainProtectedAgainstAnonymousCallers() throws Exception {
        // Endpoints not in permitAll return 401 Unauthorized for anonymous callers
        mockMvc.perform(get("/api/doctor-applications/pending"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/doctor-applications/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getHealthDoesNotRequireCsrfToken() throws Exception {
        // Safe HTTP GET does not require CSRF token
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    void controllerHasZeroDatabaseDependencies() {
        // Verify via reflection that HealthCheckController has no database or repository dependencies
        var fields = HealthCheckController.class.getDeclaredFields();
        for (var field : fields) {
            String typeName = field.getType().getName().toLowerCase();
            assertThat(typeName)
                    .doesNotContain("jdbc")
                    .doesNotContain("repository")
                    .doesNotContain("datasource")
                    .doesNotContain("entitymanager");
        }
        var constructors = HealthCheckController.class.getDeclaredConstructors();
        for (var constructor : constructors) {
            for (var paramType : constructor.getParameterTypes()) {
                String typeName = paramType.getName().toLowerCase();
                assertThat(typeName)
                        .doesNotContain("jdbc")
                        .doesNotContain("repository")
                        .doesNotContain("datasource")
                        .doesNotContain("entitymanager");
            }
        }
    }
}
