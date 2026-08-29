-- Um usuário por serviço, e é ele que faz valer o isolamento entre os schemas.
--
-- Enquanto eram três instâncias de MySQL, um serviço não tinha como alcançar o dado do
-- outro: não havia rota. Com um schema por serviço na mesma instância a rota existe, e o
-- que a fecha é o dono do dado. No PostgreSQL um schema pertence a quem o criou e nenhum
-- outro papel recebe USAGE nele — então não há REVOKE a escrever: basta cada serviço criar
-- o seu schema com o seu próprio usuário, que é o que o Flyway faz no arranque.
--
-- Os nomes usam underscore porque `user_hotel-service` seria identificador não-padrão e
-- exigiria aspas duplas em toda referência a ele.
--
-- Este arquivo roda sozinho no primeiro start do container, por estar em
-- `/docker-entrypoint-initdb.d/`. Em qualquer outro ambiente ele precisa ser executado à
-- mão, uma vez, por um superusuário, ANTES de subir os serviços. Repetir não quebra.

do $$ begin
  if not exists (select from pg_roles where rolname = 'user_hotel_service') then
    create user user_hotel_service with password 'password';
  end if;
end $$;

do $$ begin
  if not exists (select from pg_roles where rolname = 'user_booking_service') then
    create user user_booking_service with password 'password';
  end if;
end $$;

do $$ begin
  if not exists (select from pg_roles where rolname = 'user_customer_service') then
    create user user_customer_service with password 'password';
  end if;
end $$;

-- Sem isto, qualquer papel novo do banco entra por herança do PUBLIC.
revoke all on database hotel_booking_system from public;

-- `create` é o que permite ao Flyway de cada serviço criar o próprio schema — e é também o
-- que o deixa dono dele. Não dá acesso a schema nenhum que já exista.
grant connect, create on database hotel_booking_system
  to user_hotel_service, user_booking_service, user_customer_service;
