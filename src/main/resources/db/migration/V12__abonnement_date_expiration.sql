-- Abonnement : date de fin de période payée (Pro/Enterprise).
-- NULL = pas d'expiration (Free gratuit, ou tenant illimité comme l'établissement par défaut).
ALTER TABLE etablissements ADD COLUMN IF NOT EXISTS date_expiration DATE;
