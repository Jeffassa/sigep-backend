package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Paiement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.PaiementRepository;
import ci.esatic.sigep.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Enregistrement unifié d'un paiement (M6) : upgrade Pro, prolongation, idempotence. */
class PaiementServiceTest {

    private EtablissementRepository etablissementRepository;
    private PaiementRepository paiementRepository;
    private UserRepository userRepository;
    private MailService mailService;
    private PaiementService service;

    @BeforeEach
    void setUp() {
        etablissementRepository = Mockito.mock(EtablissementRepository.class);
        paiementRepository = Mockito.mock(PaiementRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        mailService = Mockito.mock(MailService.class);
        service = new PaiementService(etablissementRepository, paiementRepository,
                userRepository, new AbonnementService(), mailService);
    }

    @Test
    void paiementReussi_enregistre_passeEnPro_etProlonge() {
        Etablissement e = Etablissement.builder().nom("X").slug("x").plan(Plan.FREE).maxEnseignants(10).build();
        e.setId(5L);
        when(paiementRepository.existsByReference("Stripe abc")).thenReturn(false);
        when(etablissementRepository.findById(5L)).thenReturn(Optional.of(e));
        when(userRepository.findFirstByEtablissementIdOrderByIdAsc(5L)).thenReturn(Optional.empty());

        service.enregistrer(5L, 3, 30000L, "Stripe abc", "Stripe (en ligne)");

        ArgumentCaptor<Paiement> cap = ArgumentCaptor.forClass(Paiement.class);
        verify(paiementRepository).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getMontant()).isEqualTo(30000L);
        assertThat(cap.getValue().getMoisCredites()).isEqualTo(3);
        assertThat(cap.getValue().getReference()).isEqualTo("Stripe abc");
        assertThat(cap.getValue().getEnregistrePar()).isEqualTo("Stripe (en ligne)");
        assertThat(e.getPlan()).isEqualTo(Plan.PRO);
        assertThat(e.getMaxEnseignants()).isZero();
        assertThat(e.getDateExpiration()).isNotNull();
        verify(etablissementRepository).save(e);
    }

    @Test
    void reference_deja_traitee_estIgnoree() {
        when(paiementRepository.existsByReference("Stripe abc")).thenReturn(true);

        service.enregistrer(5L, 1, 10000L, "Stripe abc", "Stripe (en ligne)");

        verify(paiementRepository, never()).saveAndFlush(any());
        verify(etablissementRepository, never()).findById(anyLong());
    }
}
