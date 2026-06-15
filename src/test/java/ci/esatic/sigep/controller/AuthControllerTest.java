package ci.esatic.sigep.controller;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.dto.response.AuthResponse;
import ci.esatic.sigep.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AuthService authService;
    @MockBean private DataInitializer dataInitializer;

    // =========================================================================
    // POST /api/auth/login
    // =========================================================================

    @Test
    void login_devraitRetourner200AvecTokenEnCasDeSucces() throws Exception {
        AuthResponse authResp = AuthResponse.builder()
                .token("jwt-token-test")
                .type("Bearer")
                .id(1L)
                .email("admin@esatic.ci")
                .roles(List.of("ROLE_ADMIN"))
                .nom("Admin")
                .prenom("SIGEP")
                .build();

        when(authService.login(any())).thenReturn(authResp);

        String body = objectMapper.writeValueAsString(
                Map.of("email", "admin@esatic.ci", "password", "secret123")
        );

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").value("jwt-token-test"))
                .andExpect(jsonPath("$.data.email").value("admin@esatic.ci"));
    }

    @Test
    void login_devraitRetourner400SiEmailManquant() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("password", "secret123") // email absent
        );

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_devraitRetourner400SiEmailInvalide() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("email", "pas-un-email", "password", "secret123")
        );

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_devraitRetourner401SiMauvaisMotDePasse() throws Exception {
        when(authService.login(any())).thenThrow(new BadCredentialsException("Identifiants invalides"));

        String body = objectMapper.writeValueAsString(
                Map.of("email", "admin@esatic.ci", "password", "mauvais")
        );

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // POST /api/auth/register/enseignant  (ROLE_ADMIN requis)
    // =========================================================================

    @Test
    void registerEnseignant_devraitRetourner403SansAuthentification() throws Exception {
        // Spring Security 6 retourne 403 (pas 401) en l'absence d'un AuthenticationEntryPoint
        // configuré pour les API REST stateless sans token JWT fourni.
        String body = objectMapper.writeValueAsString(Map.of(
                "matricule", "ENS001",
                "nom", "Assale",
                "prenom", "Jean",
                "email", "prof@esatic.ci",
                "password", "secret123"
        ));

        mockMvc.perform(post("/api/auth/register/enseignant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerEnseignant_devraitRetourner400SiDonneesIncompletes() throws Exception {
        // password trop court (< 6 chars)
        String body = objectMapper.writeValueAsString(Map.of(
                "matricule", "ENS001",
                "nom", "Assale",
                "prenom", "Jean",
                "email", "prof@esatic.ci",
                "password", "abc"
        ));

        mockMvc.perform(post("/api/auth/register/enseignant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
