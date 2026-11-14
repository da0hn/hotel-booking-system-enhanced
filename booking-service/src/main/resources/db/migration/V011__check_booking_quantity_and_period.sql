-- A quantidade e o período que o domínio já recusa (issue #54) passam a ser recusados
-- também pelo banco. Não altera linha existente: a migration falha se alguma violar a regra.
alter table booking_room
    add constraint booking_room_quantity_ck check (quantity > 0);

alter table booking
    add constraint booking_period_ck check (check_out > check_in);
