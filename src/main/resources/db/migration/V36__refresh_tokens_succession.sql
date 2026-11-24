-- Rotation des jetons de session : l'ancien jeton n'est plus supprimé mais marqué « remplacé »,
-- avec le lien vers son successeur. Un jeton remplacé présenté une seconde fois révèle une copie.
ALTER TABLE refresh_tokens ADD COLUMN remplace_par BIGINT;
ALTER TABLE refresh_tokens ADD COLUMN remplace_le TIMESTAMP(6);
