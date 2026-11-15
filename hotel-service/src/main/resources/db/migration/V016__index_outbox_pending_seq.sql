-- O claim do relay ordena por seq e descarta a linha publicada. Com o índice único de seq,
-- um backlog grande percorre todas as publicadas (seq menor) antes da primeira pendente.
-- O índice parcial começa na pendente. O (aggregate_id, seq) da V014 continua servindo o
-- not exists que segura a ordem do agregado.
create index if not exists outbox_event_pending_seq_idx
    on outbox_event (seq)
    where published_at is null
      and abandoned_at is null;
