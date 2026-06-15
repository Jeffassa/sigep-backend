package ci.esatic.sigep.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class RattrapageRequest {

    // ID de la séance concernée (mobile envoie l'ID de séance,
    // le service en déduit la matière et la classe)
    @NotNull
    private Long seanceId;

    @NotNull
    @JsonFormat(pattern = "dd/MM/yyyy")
    private LocalDate dateSouhaitee;

    @NotNull
    @JsonFormat(pattern = "HH:mm")
    private LocalTime heureSouhaitee;

    @NotBlank
    @Size(min = 5, max = 1000, message = "Le motif doit faire entre 5 et 1000 caractères")
    private String motif;
}
