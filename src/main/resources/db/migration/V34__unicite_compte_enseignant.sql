-- V33 retirait l'unicité de enseignants.user_id sous le nom que lui donne la baseline. Une base
-- créée autrement l'aurait gardée sous un autre nom : un vacataire ne pourrait alors pas être
-- rattaché à un second établissement. On la retire ici quel que soit son nom.
DO $$
DECLARE
    contrainte record;
BEGIN
    FOR contrainte IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
        WHERE con.conrelid = 'enseignants'::regclass
          AND con.contype = 'u'
          AND array_length(con.conkey, 1) = 1
          AND att.attname = 'user_id'
    LOOP
        EXECUTE format('ALTER TABLE enseignants DROP CONSTRAINT %I', contrainte.conname);
    END LOOP;
END $$;
