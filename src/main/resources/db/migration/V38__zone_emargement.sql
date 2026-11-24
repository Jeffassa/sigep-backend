-- Zone où l'émargement est accepté : centre (degrés décimaux) et rayon (mètres).
-- Sans centre, aucun contrôle de position. Les positions des enseignants ne sont jamais stockées.
ALTER TABLE etablissements ADD COLUMN zone_latitude DOUBLE PRECISION;
ALTER TABLE etablissements ADD COLUMN zone_longitude DOUBLE PRECISION;
ALTER TABLE etablissements ADD COLUMN zone_rayon_metres INTEGER;
