-- Traçabilité de facturation : chaque paiement porte désormais SA devise et LE plan acheté.
-- Nécessaire car la devise de facturation devient configurable (passage FCFA -> EUR) : sans
-- cette colonne, les montants historiques deviendraient ambigus (10000 = FCFA ou centimes ?).
-- Le plan permet aussi au super admin de voir ce qui a été acheté (Pro vs Enterprise).
ALTER TABLE paiements ADD COLUMN IF NOT EXISTS devise VARCHAR(8);
ALTER TABLE paiements ADD COLUMN IF NOT EXISTS plan   VARCHAR(20);

-- Les paiements déjà enregistrés l'ont été en FCFA, sur le plan Pro (seul plan payant alors).
UPDATE paiements SET devise = 'XOF' WHERE devise IS NULL OR devise = '';
UPDATE paiements SET plan   = 'PRO' WHERE plan   IS NULL OR plan   = '';
