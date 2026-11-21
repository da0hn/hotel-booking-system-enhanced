-- Tentativas do relay (revisão do outbox). Sem isso uma linha que falha sempre volta a cada
-- segundo, com stacktrace, e segura os eventos seguintes do mesmo pedido.
alter table outbox_event
    add column attempts integer not null default 0,
    add column next_attempt_at timestamp with time zone,
    add column abandoned_at timestamp with time zone;
