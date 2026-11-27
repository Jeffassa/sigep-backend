-- Agent : un assistant (JEFF, ou plus tard l'agent de la console) agit sur le tableau de bord
-- d'un établissement à la place d'un administrateur.
--
-- Une clé d'agent est liée à UN compte administrateur : l'agent agit avec ses droits, relus à
-- chaque appel, dans son établissement et jamais au-delà. Comme une clé d'API, elle est traitée
-- comme un mot de passe : seule son empreinte est conservée.
CREATE TABLE IF NOT EXISTS cles_agent (
    id                   BIGSERIAL PRIMARY KEY,
    libelle              VARCHAR(120) NOT NULL,
    empreinte            VARCHAR(64)  NOT NULL UNIQUE,
    prefixe              VARCHAR(20)  NOT NULL,
    etablissement_id     BIGINT       NOT NULL REFERENCES etablissements (id),
    -- Le compte dont l'agent emprunte les droits. Sa suppression emporte la clé.
    user_id              BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    creee_le             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    derniere_utilisation TIMESTAMP,
    revoquee_le          TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_cles_agent_empreinte ON cles_agent (empreinte);
CREATE INDEX IF NOT EXISTS idx_cles_agent_etablissement ON cles_agent (etablissement_id);

-- Tout ce que l'agent a fait, ou simulé : l'établissement doit pouvoir le relire.
CREATE TABLE IF NOT EXISTS journal_agent (
    id               BIGSERIAL PRIMARY KEY,
    etablissement_id BIGINT       NOT NULL REFERENCES etablissements (id),
    cle_id           BIGINT       NOT NULL,
    user_id          BIGINT,
    date_heure       TIMESTAMP    NOT NULL,
    methode          VARCHAR(10)  NOT NULL,
    chemin           VARCHAR(255) NOT NULL,
    statut_http      INTEGER      NOT NULL,
    simulation       BOOLEAN      NOT NULL DEFAULT FALSE,
    resume           VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_journal_agent_etab_date ON journal_agent (etablissement_id, date_heure DESC);
