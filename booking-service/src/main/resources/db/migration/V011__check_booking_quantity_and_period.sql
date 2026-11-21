-- A quantidade e o período que o domínio já recusa (issue #54) passam a ser recusados
-- também pelo banco. Não altera linha existente: a migration falha, e o booking-service não
-- sobe, se alguma linha violar a regra. Confira em cada ambiente antes do deploy:
--
--   select count(*) from booking where check_in >= check_out;
--   select count(*) from booking_room where quantity <= 0;
alter table booking_room
    add constraint booking_room_quantity_ck check (quantity > 0);

alter table booking
    add constraint booking_period_ck check (check_out > check_in);
