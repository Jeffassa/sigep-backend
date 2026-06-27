package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.repository.RoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test de bout en bout de l'isolation multi-tenant via la VRAIE stack HTTP :
 * onboarding de 2 établissements, puis tentatives d'accès croisés (liste + par id).
 * Prouve qu'aucun établissement ne voit ni ne lit les données d'un autre.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MultiTenantE2ETest {

    @MockBean private DataInitializer dataInitializer; // évite de polluer la base H2 partagée

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntityManager em;

    @BeforeEach
    void setUp() {
        roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        roleRepository.findByName(ERole.ROLE_ENSEIGNANT)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ENSEIGNANT)));
    }

    @Test
    void deux_etablissements_sont_totalement_cloisonnes() throws Exception {
        String tokenA = onboard("Université A", "admin@a-e2e.ci");
        String tokenB = onboard("Université B", "admin@b-e2e.ci");

        long classeA = creerClasse(tokenA, "L3 Info A");
        creerClasse(tokenB, "L3 Info B");

        // 1) Liste : chacun ne voit QUE sa propre classe
        mockMvc.perform(get("/api/classes").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].libelle").value("L3 Info A"));

        mockMvc.perform(get("/api/classes").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].libelle").value("L3 Info B"));

        // On vide le cache de session pour que findById recharge depuis la base.
        em.flush();
        em.clear();

        // 2) Accès par id croisé : B ne doit PAS pouvoir lire la classe de A -> 404
        mockMvc.perform(get("/api/classes/" + classeA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // 3) Sanity : A lit bien la sienne
        mockMvc.perform(get("/api/classes/" + classeA).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.libelle").value("L3 Info A"));
    }

    private String onboard(String nom, String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "nomEtablissement", nom, "adminEmail", email,
                "adminPassword", "Secret@2026", "adminNom", "N", "adminPrenom", "P"));
        MvcResult res = mockMvc.perform(post("/api/saas/etablissements")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("data").get("token").asText();
    }

    private long creerClasse(String token, String libelle) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("libelle", libelle));
        MvcResult res = mockMvc.perform(post("/api/classes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }
}
