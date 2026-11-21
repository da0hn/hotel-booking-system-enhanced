-- Versão da projeção do pedido (issue #53). Duas transações que leem o mesmo pedido
-- antes de qualquer uma gravar não podem as duas sobrescrever current_status: o UPDATE
-- só passa se a versão lida ainda for a do banco.
alter table reservation_order
    add column version bigint not null default 0;
