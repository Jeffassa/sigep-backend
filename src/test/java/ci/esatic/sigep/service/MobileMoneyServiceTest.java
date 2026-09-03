package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.PaiementIntent;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.StatutIntent;
import ci.esatic.sigep.tenant.plan.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Encaissement Mobile Money : un abonnement ne doit être crédité que sur un paiement réellement
 * encaissé, conforme au contrat figé à l'initiation — et, symétriquement, un paiement encaissé
 * ne doit JAMAIS être clos en silence parce que le fournisseur était injoignable.
 */
class MobileMoneyServiceTest {

    private NovaSendService novaSend;
    private PaiementIntentStore store;
    private PlanService planService;
    private MobileMoneyService service;

    private static final String REF = "ref-123";

    @BeforeEach
    void setUp() {
        novaSend = Mockito.mock(NovaSendService.class);
        store = Mockito.mock(PaiementIntentStore.class);
        planService = Mockito.mock(PlanService.class);
        service = new MobileMoneyService(novaSend, store, planService);
        ReflectionTestUtils.setField(service, "baseUrl", "https://sigep.store");
        ReflectionTestUtils.setField(service, "expirationMinutes", 30);
        ReflectionTestUtils.setField(service, "suiviHeures", 48);
        ReflectionTestUtils.setField(service, "retentionJours", 90);
    }

    private PaiementIntent intentEnCours() {
        return PaiementIntent.builder()
                .reference(REF).etablissementId(4L).plan(Plan.PRO).mois(2)
                .montantAttendu(26000L).devise("XOF").provider("WAVE")
                .statut(StatutIntent.EN_COURS).dateCreation(LocalDateTime.now())
                .build();
    }

    private NovaSendService.Statut verdict(String status, long montant, String devise) {
        return new NovaSendService.Statut(
                new NovaSendService.Reponse("pr_1", REF, status, "accepted", null, montant, devise, null),
                NovaSendService.Etat.VERDICT);
    }

    @Test
    void paiementConforme_creditLAbonnement() {
        when(store.parReference(REF)).thenReturn(Optional.of(intentEnCours()));
        when(novaSend.statut(REF)).thenReturn(verdict("processed", 26000L, "XOF"));
        when(store.crediter(REF, 26000L)).thenReturn(StatutIntent.REUSSI);

        assertThat(service.verifierEtCrediter(REF)).isEqualTo(StatutIntent.REUSSI);
        verify(store).crediter(REF, 26000L);
    }

    /** Le webhook annonce « success » là où l'API de statut dit « processed » : les deux comptent. */
    @Test
    void statutSuccess_estAussiUnPaiementEncaisse() {
        when(store.parReference(REF)).thenReturn(Optional.of(intentEnCours()));
        when(novaSend.statut(REF)).thenReturn(verdict("success", 26000L, "XOF"));
        when(store.crediter(REF, 26000L)).thenReturn(StatutIntent.REUSSI);

        assertThat(service.verifierEtCrediter(REF)).isEqualTo(StatutIntent.REUSSI);
        verify(store).crediter(REF, 26000L);
    }

    @Test
    void montantInsuffisant_neCreditePas_etDemandeUnArbitrage() {
        when(store.parReference(REF)).thenReturn(Optional.of(intentEnCours()));
        when(novaSend.statut(REF)).thenReturn(verdict("processed", 100L, "XOF"));
        when(store.marquer(eq(REF), eq(StatutIntent.A_VERIFIER), anyString()))
                .thenReturn(StatutIntent.A_VERIFIER);

        // A_VERIFIER et non ECHOUE : l'argent est peut-être parti, on ne le nie pas au client.
        assertThat(service.verifierEtCrediter(REF)).isEqualTo(StatutIntent.A_VERIFIER);
        verify(store, never()).crediter(anyString(), anyLong());
    }

    @Test
    void deviseDifferente_neCreditePas_etDemandeUnArbitrage() {
        when(store.parReference(REF)).thenReturn(Optional.of(intentEnCours()));
        when(novaSend.statut(REF)).thenReturn(verdict("processed", 26000L, "XAF"));
        when(store.marquer(eq(REF), eq(StatutIntent.A_VERIFIER), anyString()))
                .thenReturn(StatutIntent.A_VERIFIER);

        assertThat(service.verifierEtCrediter(REF)).isEqualTo(StatutIntent.A_VERIFIER);
        verify(store, never()).crediter(anyString(), anyLong());
    }

    /**
     * RÉGRESSION CRITIQUE : une panne du fournisseur ne doit pas clore un paiement.
     * Auparavant, l'absence de réponse déclenchait une expiration définitive et l'argent
     * réellement encaissé n'était jamais crédité.
     */
    @Test
    void fournisseurInjoignable_laisseLIntentionEnCours() {
        when(store.parReference(REF)).thenReturn(Optional.of(intentEnCours()));
        when(novaSend.statut(REF)).thenReturn(
                new NovaSendService.Statut(null, NovaSendService.Etat.INJOIGNABLE));

        assertThat(service.verifierEtCrediter(REF)).isEqualTo(StatutIntent.EN_COURS);
        verify(store, never()).marquer(anyString(), Mockito.any(), anyString());
        verify(store, never()).crediter(anyString(), anyLong());
    }

    /** Hors fenêtre de suivi sans verdict : arbitrage humain, jamais un échec inventé. */
    @Test
    void aucunVerdictAuDelaDeLaFenetre_partEnArbitrage() {
        PaiementIntent vieille = intentEnCours();
        vieille.setDateCreation(LocalDateTime.now().minusHours(72));   // > suivi-heures (48)
        when(store.parReference(REF)).thenReturn(Optional.of(vieille));
        when(novaSend.statut(REF)).thenReturn(
                new NovaSendService.Statut(null, NovaSendService.Etat.INJOIGNABLE));
        when(store.marquer(eq(REF), eq(StatutIntent.A_VERIFIER), anyString()))
                .thenReturn(StatutIntent.A_VERIFIER);

        assertThat(service.verifierEtCrediter(REF)).isEqualTo(StatutIntent.A_VERIFIER);
        verify(store, never()).crediter(anyString(), anyLong());
    }

    @Test
    void paiementEnCours_neCreditePas() {
        when(store.parReference(REF)).thenReturn(Optional.of(intentEnCours()));
        when(novaSend.statut(REF)).thenReturn(verdict("processing", 0L, "XOF"));

        assertThat(service.verifierEtCrediter(REF)).isEqualTo(StatutIntent.EN_COURS);
        verify(store, never()).crediter(anyString(), anyLong());
    }

    @Test
    void intentionDejaTranchee_nEstPasRejouee() {
        PaiementIntent deja = intentEnCours();
        deja.setStatut(StatutIntent.REUSSI);
        when(store.parReference(REF)).thenReturn(Optional.of(deja));

        assertThat(service.verifierEtCrediter(REF)).isEqualTo(StatutIntent.REUSSI);
        verify(novaSend, never()).statut(anyString());       // pas même d'appel réseau
        verify(store, never()).crediter(anyString(), anyLong());
    }

    @Test
    void normalisationDuNumero_exigeUnIndicatifPays() {
        assertThat(service.normaliserMsisdn("+225 07 00 00 00 00")).isEqualTo("+2250700000000");
        assertThat(service.normaliserMsisdn("002250700000000")).isEqualTo("+2250700000000");
        assertThat(service.normaliserMsisdn("0700000000")).isNull();   // sans indicatif : refusé
        assertThat(service.normaliserMsisdn("abc")).isNull();
        assertThat(service.normaliserMsisdn(null)).isNull();
    }
}
