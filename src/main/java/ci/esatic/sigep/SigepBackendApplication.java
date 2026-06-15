package ci.esatic.sigep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SigepBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(SigepBackendApplication.class, args);
	}

}
