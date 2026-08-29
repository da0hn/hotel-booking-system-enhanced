create table if not exists room
(
    id            varchar(36)    not null,
    hotel_id      varchar(36)    not null,
    current_price numeric(10, 2) not null,
    capacity      int            not null,
    quantity      int            not null,
    constraint room_pk primary key (id)
);
