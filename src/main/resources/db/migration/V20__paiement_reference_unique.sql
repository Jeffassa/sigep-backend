-- Idempotence des paiements (sécurité) : empêche le double crédit d'abonnement en cas de
-- livraison concurrente d'un webhook (Stripe / Mobile Money) avec la même référence.
-- En Postgres, une contrainte UNIQUE considère les NULL comme distincts : les paiements
-- manuels sans référence (reference NULL) restent autorisés en multiple.
ALTER TABLE paiements ADD CONSTRAINT uk_paiements_reference UNIQUE (reference);
