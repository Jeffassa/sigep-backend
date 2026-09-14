package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Une seule devise sur la page des tarifs.
 *
 * <p>Le plan gratuit affichait « 0€ » en dur au-dessus de deux prix en francs CFA. La devise de
 * facturation est pourtant une source unique : elle commande l'affichage <i>et</i> ce que Stripe
 * débite, précisément pour que les deux ne puissent pas diverger. Une devise écrite à la main
 * dans un gabarit échappait à cette garantie.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TarifsAffichesTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    @Test
    void laGrilleTarifaire_neMelangePasLesDevises() throws Exception {
        String html = mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        assertThat(html).as("aucun montant ne doit etre libelle en euros")
                .doesNotContain("0€").doesNotContain("0 €");
        assertThat(html).contains("FCFA");
    }
}
