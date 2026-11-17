CREATE TABLE IF NOT EXISTS listing (
                                       id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120) NOT NULL CHECK (btrim(name) <> ''),
    description TEXT         NOT NULL DEFAULT ''
    );

INSERT INTO listing (id, name, description)
VALUES ('00000000-0000-0000-0000-000000000001', 'Room near campus', 'Furnished 12 m2 room, 5 minutes walk to campus. Shared kitchen.'),
       ('00000000-0000-0000-0000-000000000002', 'Studio apartment', '30 m2 studio with own kitchen and bathroom. Close to the bus station.'),
       ('00000000-0000-0000-0000-000000000003', 'Shared flat, 2 rooms', 'Two rooms in a flat with two other students. Rent includes internet.'),
       ('00000000-0000-0000-0000-000000000004', 'Summer sublet', '')
    ON CONFLICT (id) DO NOTHING;