-- Eventos já consumidos por este serviço (issue #7). O RabbitMQ entrega ao menos uma vez, e o
-- listener registra aqui o `event_id` na mesma transação do efeito: a chave primária é o árbitro
-- de quem processa quando a mesma mensagem chega duas vezes.
create table if not exists processed_event
(
    event_id     varchar(36)              not null,
    event_type   varchar(255)             not null,
    processed_at timestamp with time zone not null default now(),
    constraint processed_event_pk primary key (event_id)
);

-- Serve ao expurgo periódico, que apaga por `processed_at`.
create index if not exists processed_event_processed_at_idx
    on processed_event (processed_at);
