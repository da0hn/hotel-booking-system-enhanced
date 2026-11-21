-- Eventos da saga gravados na mesma transação do efeito (issue #35). O relay publica a linha
-- e só então preenche published_at. `seq` é a ordem: now() não distingue dois inserts da mesma
-- transação, e o id é um UUID.
create table if not exists outbox_event
(
    id            uuid                     not null,
    seq           bigint generated always as identity not null,
    aggregate_id  varchar(36)              not null,
    event_type    varchar(255)             not null,
    exchange      varchar(255)             not null,
    routing_key   varchar(255)             not null,
    payload       jsonb                    not null,
    created_at    timestamp with time zone not null default now(),
    published_at  timestamp with time zone,
    constraint outbox_event_pk primary key (id),
    constraint outbox_event_seq_uk unique (seq)
);

-- O relay busca o pendente mais antigo de cada agregado. O predicado parcial deixa a linha
-- publicada fora do índice.
create index if not exists outbox_event_pending_idx
    on outbox_event (aggregate_id, seq)
    where published_at is null;

-- Serve ao expurgo, que apaga por published_at.
create index if not exists outbox_event_published_at_idx
    on outbox_event (published_at);
