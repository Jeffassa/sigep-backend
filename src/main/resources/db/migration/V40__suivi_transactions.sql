-- Suivi des transactions par la plateforme : qui a tranché une transaction à la main, quand et pourquoi.
ALTER TABLE paiement_intents ADD COLUMN traite_par VARCHAR(180);
ALTER TABLE paiement_intents ADD COLUMN date_traitement TIMESTAMP;
ALTER TABLE paiement_intents ADD COLUMN note_traitement VARCHAR(500);

-- Période d'abonnement couverte par chaque paiement. Vide pour les paiements antérieurs :
-- l'historique la reconstitue alors en rejouant les paiements dans l'ordre.
ALTER TABLE paiements ADD COLUMN periode_debut DATE;
ALTER TABLE paiements ADD COLUMN periode_fin DATE;
