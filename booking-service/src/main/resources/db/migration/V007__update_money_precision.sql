-- Ver a nota da migração equivalente no hotel-service: a escala 4 é a do cálculo, não a da
-- exibição. Aqui ela importa ainda mais, porque `Booking.validateTotalPrice()` confronta a
-- soma dos itens com o total recebido no evento — dois números que precisam ser produzidos
-- com a mesma escala, sob pena de a reserva ser recusada com BOOKING_TOTAL_PRICE_INVALID
-- por um centavo de arredondamento.
alter table booking
    alter column total_price type numeric(19, 4);

alter table booking_room
    alter column price type numeric(19, 4);

alter table room
    alter column current_price type numeric(19, 4);
