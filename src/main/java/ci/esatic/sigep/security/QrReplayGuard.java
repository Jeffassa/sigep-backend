package ci.esatic.sigep.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-rejeu des tokens QR d'émargement.
 * Un même token (jti) ne peut être consommé qu'une seule fois PAR enseignant :
 * cela empêche le rejeu d'une capture d'écran et l'émargement multiple avec un
 * seul scan, tout en laissant plusieurs enseignants scanner le même QR universel.
 *
 * État en mémoire (suffisant pour un déploiement mono-instance, comme le rate-limiter).
 */
@Component
public class QrReplayGuard {

    private static final long TTL_MS = 60_000L; // > durée de vie d'un token (30 s)

    private final ConcurrentHashMap<String, Long> consommes = new ConcurrentHashMap<>();

    /**
     * Tente de consommer le token pour cet enseignant.
     * @return true si c'est la première utilisation (autorisé), false si déjà utilisé (rejeu).
     */
    public boolean tryConsume(Long enseignantId, String jti) {
        if (jti == null || jti.isBlank()) return true; // pas de jti → anti-rejeu non applicable
        purge();
        String cle = enseignantId + ":" + jti;
        return consommes.putIfAbsent(cle, System.currentTimeMillis() + TTL_MS) == null;
    }

    private void purge() {
        long now = System.currentTimeMillis();
        consommes.entrySet().removeIf(e -> e.getValue() < now);
    }
}
