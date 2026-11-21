-- A ocupação filtra por quarto (issue #55). O PostgreSQL não cria índice para coluna de FK.
create index if not exists booking_room_room_id_idx on booking_room (room_id);
