-- Ver a nota da migração equivalente no hotel-service: a escala 4 é a do cálculo, não a da
-- exibição. O customer-service é read-model — ele não calcula nada —, mas guarda o total na
-- mesma escala em que ele foi calculado e trafegou no evento, para que a timeline mostrada
-- ao cliente não divirja do que o booking gravou.
alter table reservation_order
    alter column total_price type numeric(19, 4);
