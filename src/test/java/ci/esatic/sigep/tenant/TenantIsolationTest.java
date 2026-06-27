package ci.esatic.sigep.tenant;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.Classe;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prouve l'isolation tenant : avec le filtre activé pour un établissement, on ne lit
 * QUE ses données ; et une création est estampillée automatiquement au bon tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TenantIsolationTest {

    // Mocké : évite de commiter l'admin/établissement par défaut dans la base H2
    // partagée entre contextes de test.
    @MockBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityManager em;
    @Autowired
    private EnseignantRepository enseignantRepository;
    @Autowired
    private EtablissementRepository etablissementRepository;
    @Autowired
    private ClasseRepository classeRepository;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        em.unwrap(Session.class).disableFilter("tenantFilter");
    }

    @Test
    void un_etablissement_ne_voit_que_ses_propres_enseignants() {
        Long a = etablissementRepository.save(
                Etablissement.builder().nom("Université A").slug("univ-a").plan(Plan.PRO).build()).getId();
        Long b = etablissementRepository.save(
                Etablissement.builder().nom("Université B").slug("univ-b").plan(Plan.PRO).build()).getId();
        enseignantRepository.save(enseignant("MAT-A", a));
        enseignantRepository.save(enseignant("MAT-B", b));
        em.flush();
        em.clear();

        activerFiltre(a);
        List<Enseignant> vusParA = enseignantRepository.findAll();
        assertThat(vusParA).isNotEmpty();
        assertThat(vusParA).allMatch(e -> a.equals(e.getEtablissementId()));
        assertThat(vusParA).extracting(Enseignant::getMatricule).containsExactly("MAT-A");

        em.clear();
        activerFiltre(b);
        List<Enseignant> vusParB = enseignantRepository.findAll();
        assertThat(vusParB).allMatch(e -> b.equals(e.getEtablissementId()));
        assertThat(vusParB).extracting(Enseignant::getMatricule).containsExactly("MAT-B");
    }

    @Test
    void creation_estampillee_automatiquement_au_tenant_courant() {
        Long c = etablissementRepository.save(
                Etablissement.builder().nom("Université C").slug("univ-c").build()).getId();
        em.flush();

        TenantContext.set(c); // simule le contexte posé par TenantInterceptor

        Enseignant sansTenant = Enseignant.builder().matricule("MAT-C").nom("N").prenom("P").build();
        Enseignant saved = enseignantRepository.save(sansTenant);
        em.flush();

        assertThat(saved.getEtablissementId()).isEqualTo(c);
    }

    @Test
    void isolation_appliquee_aussi_aux_referentiels_classe() {
        Long a = etablissementRepository.save(
                Etablissement.builder().nom("Univ A").slug("ua").plan(Plan.PRO).build()).getId();
        Long b = etablissementRepository.save(
                Etablissement.builder().nom("Univ B").slug("ub").plan(Plan.PRO).build()).getId();
        classeRepository.save(classe("L3-A", a));
        classeRepository.save(classe("L3-B", b));
        em.flush();
        em.clear();

        activerFiltre(a);
        assertThat(classeRepository.findAll())
                .extracting(Classe::getLibelle).containsExactly("L3-A");

        em.clear();
        activerFiltre(b);
        assertThat(classeRepository.findAll())
                .extracting(Classe::getLibelle).containsExactly("L3-B");
    }

    private Classe classe(String libelle, Long etablissementId) {
        Classe c = Classe.builder().libelle(libelle).build();
        c.setEtablissementId(etablissementId);
        return c;
    }

    private void activerFiltre(Long tenant) {
        Session s = em.unwrap(Session.class);
        s.disableFilter("tenantFilter");
        s.enableFilter("tenantFilter").setParameter("tenantId", tenant);
    }

    private Enseignant enseignant(String matricule, Long etablissementId) {
        Enseignant e = Enseignant.builder().matricule(matricule).nom("Nom").prenom("Prénom").build();
        e.setEtablissementId(etablissementId);
        return e;
    }
}
