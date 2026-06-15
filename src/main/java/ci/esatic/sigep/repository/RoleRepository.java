package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Integer> {
    Optional<Role> findByName(ERole name);
}
