package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.ApplicationMobileRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * L'APK déposé par la plateforme est servi depuis sigep.store, à l'identique, et l'établissement
 * en envoie le lien à ses enseignants.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationMobileTest {

    @MockitoBean private DataInitializer dataInitializer;
    @MockitoBean private MailService mailService;

    @Autowired private MockMvc mockMvc;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private ApplicationMobileRepository applicationRepository;

    private User superAdmin;
    private User admin;
    private Etablissement ecole;
    private Enseignant awa;
    private Enseignant kofi;
    /** Établissements créés par le test : effacés après chacun (le téléchargement asynchrone exclut @Transactional). */
    private final java.util.List<Etablissement> crees = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        Role roleSuper = roleRepository.findByName(ERole.ROLE_SUPER_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_SUPER_ADMIN)));
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        Role roleEns = roleRepository.findByName(ERole.ROLE_ENSEIGNANT)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ENSEIGNANT)));
        Etablissement plateforme = etablissementRepository.save(Etablissement.builder()
                .nom("SIGEP Plateforme").slug("plateforme-apk-" + n).build());
        ecole = etablissementRepository.save(Etablissement.builder().nom("Lycée APK").slug("apk-" + n).build());
        crees.add(plateforme);
        crees.add(ecole);
        superAdmin = userRepository.save(User.builder().email("root-apk-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleSuper))).etablissement(plateforme).build());
        admin = userRepository.save(User.builder().email("adm-apk-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(ecole).build());

        awa = enseignant(n, "A", "Awa", roleEns, StatutEnseignant.VALIDATED, true);
        kofi = enseignant(n, "B", "Kofi", roleEns, StatutEnseignant.VALIDATED, true);
        enseignant(n, "C", "Sans", roleEns, StatutEnseignant.PENDING, false);       // pas de compte : pas d'envoi
        enseignant(n, "D", "Parti", roleEns, StatutEnseignant.ARCHIVE, true);     // a quitté l'établissement
    }

    @org.junit.jupiter.api.AfterEach
    void nettoyer() {
        java.util.Set<Long> ids = crees.stream().map(Etablissement::getId).collect(java.util.stream.Collectors.toSet());
        enseignantRepository.deleteAll(enseignantRepository.findAll().stream()
                .filter(x -> ids.contains(x.getEtablissementId())).toList());
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(u -> u.getEtablissement() != null && ids.contains(u.getEtablissement().getId())).toList());
        etablissementRepository.deleteAll(crees);
        crees.clear();
        applicationRepository.deleteAll();
    }

    private Enseignant enseignant(long n, String suffixe, String prenom, Role role, StatutEnseignant statut,
                                  boolean avecCompte) {
        // Le compte est enregistré avec la fiche (cascade), comme le fait la console.
        User u = avecCompte ? User.builder()
                .email(prenom.toLowerCase() + "-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(role))).etablissement(ecole).build() : null;
        return enseignantRepository.save(Enseignant.builder().matricule("APK" + suffixe + n).nom("Nom" + suffixe)
                .prenom(prenom).statut(statut).user(u).etablissementId(ecole.getId()).build());
    }

    /** Un faux APK : l'en-tête ZIP suivi de 2,5 Mo d'octets, soit trois morceaux. */
    private static byte[] apk(int graine) {
        byte[] octets = new byte[2 * 1024 * 1024 + 512 * 1024];
        new Random(graine).nextBytes(octets);
        octets[0] = 0x50; octets[1] = 0x4B; octets[2] = 0x03; octets[3] = 0x04;
        return octets;
    }

    private MvcResult deposer(String version, byte[] contenu) throws Exception {
        return mockMvc.perform(post("/plateforme/application/depot").with(user(superAdmin)).with(csrf())
                        .param("version", version)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(contenu))
                .andReturn();
    }

    @Test
    void lApkDepose_seTelechargeALIdentique_etRemplaceLePrecedent() throws Exception {
        assertThat(deposer("1.2.0", apk(1)).getResponse().getStatus()).isEqualTo(200);
        byte[] contenu = apk(2);
        MvcResult depot = deposer("1.2.1", contenu);
        assertThat(depot.getResponse().getStatus()).isEqualTo(200);
        assertThat(applicationRepository.count()).isEqualTo(1);

        MvcResult debut = mockMvc.perform(get("/telecharger/sigep-android.apk"))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult fin = mockMvc.perform(asyncDispatch(debut))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.android.package-archive"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("SIGEP-1.2.1.apk")))
                .andReturn();
        byte[] recu = fin.getResponse().getContentAsByteArray();
        assertThat(recu).isEqualTo(contenu);
        assertThat(fin.getResponse().getHeader("X-Content-SHA256"))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu)));
    }

    @Test
    void unFichierQuiNEstPasUnApk_ouUneVersionIllisible_sontRefuses() throws Exception {
        deposer("1.2.1", apk(3));
        long avant = applicationRepository.count();

        MvcResult pasApk = deposer("1.2.2", "bonjour, je ne suis pas un APK".getBytes());
        assertThat(pasApk.getResponse().getStatus()).isEqualTo(400);
        assertThat(pasApk.getResponse().getContentAsString()).contains("pas un APK");
        assertThat(deposer("version 2", apk(4)).getResponse().getStatus()).isEqualTo(400);

        // Le refus n'a rien effacé : la version en ligne reste la précédente.
        assertThat(applicationRepository.count()).isEqualTo(avant);
        assertThat(applicationRepository.findFirstByOrderByDateDepotDescIdDesc().orElseThrow().getVersion())
                .isEqualTo("1.2.1");
    }

    @Test
    void seuleLaPlateformeDepose() throws Exception {
        mockMvc.perform(post("/plateforme/application/depot").with(user(admin)).with(csrf())
                        .param("version", "9.9.9").contentType(MediaType.APPLICATION_OCTET_STREAM).content(apk(5)))
                .andExpect(status().isForbidden());
    }

    @Test
    void lEtablissementEnvoieLeLien_aSesEnseignantsActifs() throws Exception {
        deposer("1.2.1", apk(6));

        mockMvc.perform(get("/admin/enseignants/application-mobile").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1.2.1</span> pour Android")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/telecharger/sigep-android.apk")));

        mockMvc.perform(post("/admin/enseignants/application-mobile/envoyer").with(user(admin)).with(csrf()))
                .andExpect(flash().attribute("ok", "Lien envoyé à 2 enseignants."));
        verify(mailService, times(2)).notifierApplicationMobile(any(), anyString(), anyString(),
                eq("Lycée APK"), eq("1.2.1"), eq("https://sigep.store/telecharger/sigep-android.apk"));
        verify(mailService).notifierApplicationMobile(any(), eq(awa.getUser().getEmail()), eq("Awa"),
                any(), any(), any());

        // Un second envoi à tous, aussitôt après, est retenu ; l'envoi à un seul reste possible.
        mockMvc.perform(post("/admin/enseignants/application-mobile/envoyer").with(user(admin)).with(csrf()))
                .andExpect(flash().attributeExists("erreur"));
        mockMvc.perform(post("/admin/enseignants/application-mobile/envoyer").with(user(admin)).with(csrf())
                        .param("enseignantId", kofi.getId().toString()))
                .andExpect(flash().attribute("ok", "Lien envoyé à " + kofi.getUser().getEmail() + "."));
        verify(mailService, times(3)).notifierApplicationMobile(any(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    void unEnseignantDUnAutreEtablissement_neRecoitRien() throws Exception {
        deposer("1.2.1", apk(7));
        Etablissement autre = etablissementRepository.save(Etablissement.builder()
                .nom("Autre").slug("autre-apk-" + System.nanoTime()).build());
        crees.add(autre);
        Enseignant etranger = enseignantRepository.save(Enseignant.builder().matricule("X" + System.nanoTime())
                .nom("X").prenom("Y").statut(StatutEnseignant.VALIDATED)
                .user(User.builder().email("etranger-" + System.nanoTime() + "@test.local").password("{noop}x")
                        .etablissement(autre).build())
                .etablissementId(autre.getId()).build());

        mockMvc.perform(post("/admin/enseignants/application-mobile/envoyer").with(user(admin)).with(csrf())
                        .param("enseignantId", etranger.getId().toString()))
                .andExpect(flash().attributeExists("erreur"));
        verify(mailService, never()).notifierApplicationMobile(any(), any(), any(), any(), any(), any());
    }
}
