-- Intentions de paiement Mobile Money (NovaSend).
--
-- POURQUOI cette table : NovaSend n'a pas de webhook. La confirmation se fait en interrogeant
-- GET /v1/payin/{reference}. Il faut donc mémoriser, AU MOMENT DE L'INITIATION, ce que le client
-- est censé payer (établissement, plan, nombre de mois, montant attendu). Sans cela :
--   - impossible de vérifier que le montant réellement payé correspond au plan demandé
--     (un client pourrait payer 100 F et se voir créditer 12 mois d'Enterprise) ;
--   - impossible de créditer un paiement confirmé si le client ferme son navigateur
--     (le relanceur périodique s'appuie sur les intentions encore EN_COURS).
CREATE TABLE IF NOT EXISTS paiement_intents (
    id                BIGSERIAL PRIMARY KEY,
    reference         VARCHAR(64)  NOT NULL,
    etablissement_id  BIGINT       NOT NULL,
    plan              VARCHAR(20)  NOT NULL,
    mois              INT          NOT NULL,
    montant_attendu   BIGINT       NOT NULL,
    devise            VARCHAR(8)   NOT NULL,
    msisdn            VARCHAR(32),
    provider          VARCHAR(20),
    statut            VARCHAR(20)  NOT NULL,
    novasend_id       VARCHAR(80),
    payment_url       VARCHAR(512),
    message           VARCHAR(512),
    date_creation     TIMESTAMP    NOT NULL,
    date_maj          TIMESTAMP,
    CONSTRAINT uk_paiement_intents_reference UNIQUE (reference)
);

-- Le relanceur balaye les intentions encore en cours : index sur (statut, date_creation).
CREATE INDEX IF NOT EXISTS idx_paiement_intents_statut ON paiement_intents (statut, date_creation);
CREATE INDEX IF NOT EXISTS idx_paiement_intents_etab   ON paiement_intents (etablissement_id);
