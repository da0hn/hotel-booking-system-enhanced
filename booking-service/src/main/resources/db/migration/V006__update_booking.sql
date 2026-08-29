alter table booking
    add column created_at timestamp with time zone default now();

alter table booking
    add column updated_at timestamp with time zone default now();
