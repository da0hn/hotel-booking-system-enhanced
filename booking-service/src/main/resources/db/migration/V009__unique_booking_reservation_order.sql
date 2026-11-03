-- Um pedido tem no máximo uma reserva (issue #7). Rede de segurança do banco: a camada de
-- aplicação já barra a repetição, e esta constraint garante que, se duas entregas simultâneas
-- passarem por ela, a segunda falha em vez de gravar uma reserva duplicada.
--
-- ATENÇÃO ao aplicar em um banco que já recebeu reentregas: se houver pedido com mais de uma
-- reserva, esta migration FALHA e o serviço não sobe. Confira antes, e resolva à mão (a escolha
-- de qual reserva manter é decisão de negócio e não cabe a uma migration):
--
--   select reservation_order_id, count(*) from booking
--   group by reservation_order_id having count(*) > 1;
alter table booking
    add constraint booking_reservation_order_id_uk unique (reservation_order_id);
