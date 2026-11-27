package ci.esatic.sigep;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SigepBackendApplicationTests {

	// Empêche le DataInitializer d'insérer des données en base au démarrage du test
	@MockitoBean
	DataInitializer dataInitializer;

	@Test
	void contextLoads() {
	}

}
