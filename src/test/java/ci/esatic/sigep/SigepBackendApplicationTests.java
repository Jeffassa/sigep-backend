package ci.esatic.sigep;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SigepBackendApplicationTests {

	// Empêche le DataInitializer d'insérer des données en base au démarrage du test
	@MockBean
	DataInitializer dataInitializer;

	@Test
	void contextLoads() {
	}

}
