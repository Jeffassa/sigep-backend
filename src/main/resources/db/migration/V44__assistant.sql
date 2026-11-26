-- Assistant de la console : un agent conversationnel, côté serveur, qui pilote le tableau de bord
-- avec les mêmes outils que l'API d'agent.
--
-- Il envoie des données à un fournisseur d'IA situé hors de Côte d'Ivoire : chaque établissement
-- doit l'activer lui-même, et l'on garde qui l'a fait, et quand.
ALTER TABLE etablissements ADD COLUMN IF NOT EXISTS assistant_active BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE etablissements ADD COLUMN IF NOT EXISTS assistant_active_le TIMESTAMP;
ALTER TABLE etablissements ADD COLUMN IF NOT EXISTS assistant_active_par VARCHAR(255);

-- Le journal de l'agent reçoit aussi les actions de l'assistant, qui n'a pas de clé.
ALTER TABLE journal_agent ALTER COLUMN cle_id DROP NOT NULL;
ALTER TABLE journal_agent ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'AGENT';

-- Consommation de l'assistant, par établissement et par jour : borne la facture du modèle.
CREATE TABLE IF NOT EXISTS assistant_conso (
    id               BIGSERIAL PRIMARY KEY,
    etablissement_id BIGINT  NOT NULL REFERENCES etablissements (id) ON DELETE CASCADE,
    jour             DATE    NOT NULL,
    requetes         INTEGER NOT NULL DEFAULT 0,
    jetons_entree    BIGINT  NOT NULL DEFAULT 0,
    jetons_sortie    BIGINT  NOT NULL DEFAULT 0,
    CONSTRAINT uk_assistant_conso_jour UNIQUE (etablissement_id, jour)
);
