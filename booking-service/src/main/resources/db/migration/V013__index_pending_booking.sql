-- A varredura de reservas PENDING filtra por created_at (issue #8). Sem o índice ela lê a
-- tabela inteira a cada cinco minutos, e a tabela não é expurgada.
create index if not exists booking_pending_created_at_idx
    on booking (created_at)
    where status = 'PENDING';
