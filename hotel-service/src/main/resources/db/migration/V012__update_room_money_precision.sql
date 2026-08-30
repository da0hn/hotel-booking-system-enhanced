-- A escala 4 não é o número de casas que o preço mostra — são as que ele pode acumular sem
-- arredondar antes da hora. A saga soma diárias e multiplica por quantidade antes de o valor
-- chegar a qualquer olho humano, e é nesse meio do caminho que a escala 2 perderia centavo:
-- arredondar cada parcela e só depois somar não dá o mesmo que somar e arredondar no fim.
-- Quem exibe (o `GET /hotel`) reduz para 2 casas no mapper de saída; o banco guarda o
-- valor de cálculo.
--
-- A precisão 19 é folga deliberada: 15 dígitos inteiros não restringem nada que uma diária
-- de hotel possa valer, e evita ter de mexer no schema de novo se o domínio mudar de moeda.
alter table room
    alter column current_price type numeric(19, 4);
