
DROP TABLE IF EXISTS m1 CASCADE;
DROP TABLE IF EXISTS m2 CASCADE;
CREATE TABLE m1(
    mid bigint,
    value bigint,
    id bigint,
    cardinality int,
    predicate int);

CREATE TABLE m2(
    mid bigint,
    value bigint,
    id bigint);

INSERT INTO m1(mid, value, cardinality, predicate) VALUES
    (0, 0, 2, 1),
    (1, 0, 3, 2),
    (2, NULL, 4, 3);

INSERT INTO m2(mid, value) VALUES
    (0, 0),
    (1, 0),
    (2, 1),
    (3, NULL);
