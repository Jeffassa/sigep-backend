package ci.esatic.sigep.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalleResponse {
    private Long id;
    private String code;
    private String batiment;
    private Integer capacite;
}
