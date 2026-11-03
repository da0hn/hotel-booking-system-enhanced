# AGENTS.md — commons

Instruções para quem mexe em `commons/`. Complementam o `AGENTS.md` da raiz, onde ficam as
regras do contrato de mensagens que valem para todo o reactor (FQCN, `TrustedEventPackages`,
`AbstractRabbitPublisher` e a fila de negócio por `DeadLetterTopology.queue(...)`).

## Contrato de eventos: Jackson e Lombok

- O Jackson só constrói uma classe se enxergar **um** creator. Com um construtor público
  único ele o usa sozinho, sem configuração nenhuma; com dois candidatos ele não desempata
  e recusa a mensagem. O `@SuperBuilder` cria exatamente esse segundo candidato — o
  construtor que recebe o builder —, então **toda classe de evento com `@SuperBuilder`
  precisa de `@Jacksonized`**, que manda o Jackson desserializar pelo próprio builder.
- O `@Jacksonized` depende de `lombok.config` na raiz: sem
  `lombok.jacksonized.jacksonVersion += 3` o Lombok gera as anotações do Jackson **2** e a
  compilação quebra com `package com.fasterxml.jackson.databind.annotation does not exist`.
  A chave é de lista — com `=` no lugar de `+=` ela é ignorada em silêncio. O Lombok erra
  o palpite sozinho porque o Jackson 3 usa o `jackson-annotations` 2.x, e ver
  `com.fasterxml.jackson.annotation` no classpath o convence de que o Jackson 2 está lá.
- O mesmo vale para `@Builder` + `@AllArgsConstructor`: sem `@Jacksonized` o Jackson chama o
  construtor direto, e o `@Builder.Default` do `eventId` não se aplica — o campo ausente (mensagem
  legada) chega `null` em vez de gerado. Todo evento declara `eventId` com `@Builder.Default`.
- `EventContractTest`, no `commons`, varre o pacote e prova que cada classe concreta de
  evento é construível e que o `eventId` sobrevive à conversão AMQP. Foi escrito depois que
  essa falha passou por um `mvn verify` verde: ela não aparece no arranque nem nos testes de
  domínio, só no listener — serviços saudáveis, `POST /hotel/booking` respondendo 200 e a saga
  parada. Evento novo entra na cobertura sozinho; não há lista para manter.

## Retry e DLQ: detalhes

- **Retry sem estado, com backoff exponencial**: `max-retries: 2` (três tentativas ao todo),
  1s → 2s, teto de 10s, em `spring.rabbitmq.listener.simple.retry.*`. O Boot 4 **não tem
  `max-attempts`** e ignora em silêncio chave que não conhece — o
  `DeadLetterConfigurationTest` de cada serviço lê o `application.yml` real e prova que o
  binding pegou. Cada tentativa roda numa transação própria (o interceptor envolve o
  listener por fora), e a thread do consumidor fica bloqueada durante o backoff: uma
  mensagem venenosa ocupa um dos consumidores por 2 a 4,5s. Como todo listener roda com no
  mínimo 3 consumidores, os outros seguem; as mensagens que o broker já entregou àquele
  consumidor (`prefetch` padrão de 250) esperam atrás dela.
- **O jitter tem chave própria, `app.rabbitmq.retry.jitter`.** O Boot 4.1 não expõe jitter
  em `spring.rabbitmq.listener.simple.retry.*`, embora o `RetryPolicySettings` o suporte, e
  uma chave desconhecida sob esse prefixo seria ignorada em silêncio. Quem a lê é o
  `deadLetterRetrySettingsCustomizer` (`DeadLetterRetryConfiguration.JITTER_PROPERTY`). Cada
  espera sai sorteada entre `intervalo - jitter` e `intervalo + jitter`, sem ficar abaixo do
  intervalo inicial nem acima do teto, e o multiplicador age sobre o jitter também: com
  500ms, a primeira espera fica em 1–1,5s e a segunda em 1–3s. Sem a chave não há jitter.
- **Esgotadas as tentativas**, o `RepublishMessageRecoverer` publica a mensagem no DLX com a
  routing key `<fila>.dlq`, preservando o corpo e acrescentando `x-exception-message`,
  `x-exception-stacktrace`, `x-original-exchange` e `x-original-routingKey`. Os argumentos
  de DLX na fila são a rede de segurança para uma rejeição que não passe pelo recoverer —
  daí o `default-requeue-rejected: false`.
- **Erro determinístico não é retentado.** O `exceptionPredicate` percorre a cadeia de
  causas e trata como definitiva a que contenha `MessageConversionException` (JSON
  malformado, classe inexistente, **pacote fora do `TrustedEventPackages`**) ou
  `UnsupportedEventTypeException` (subtipo de evento sem ramo no `switch`). A primeira é
  fabricada pelo `EventMessageConverter`, que traduz o `IllegalArgumentException` cru do
  Jackson; sem ele, tipo não confiável seria retentado três vezes sem chance de dar certo.
- O `default ->` dos handlers do `hotel-service` é **inalcançável hoje**: as hierarquias são
  `sealed` e os `case` as esgotam. Nenhum teste o exercita (o Mockito não mocka classe
  sealed). Ele existe para o dia em que um subtipo novo entrar na hierarquia e ninguém
  lembrar do `switch`.
- O que depende do broker é provado pelo `DeadLetterRetryIT` (`commons`), que sobe um
  RabbitMQ real por Testcontainers: o que se quer saber é se a mensagem chega à DLQ com os
  headers certos, e um broker simulado não responde isso.
- **Morte do processo no meio do consumo não passa pelo retry nem pela DLQ.** O ack é
  automático (nenhum `application.yml` configura `acknowledge-mode`): só sai depois que o
  listener retorna. Sem ack, o broker devolve a mensagem à fila marcada como reentrega, e o
  contador de tentativas, que vive na memória do processo, recomeça do zero. O lado seguro está
  provado em dois níveis: o `DeadLetterRetryIT` mata um consumidor sem ack e confere a
  reentrega fora da DLQ, e o `SagaIT` derruba o `booking-service` com `kill` dentro da
  transação e confere que a saga retoma com uma única reserva. O lado inseguro — morrer depois
  do commit e antes do ack — repete o efeito, e o `DeadLetterRetryIT` o fixa de propósito:
  deduplicar é papel do consumidor (#7): o `IdempotentEventConsumer` mora no `commons`, mas cada
  listener opta por usá-lo, e o listener-sonda do `DeadLetterRetryIT` não usa — por isso o teste
  continua afirmando a garantia crua do broker. A prova de que a reentrega não repete o efeito
  está no `BookingIdempotencyIT` (`booking-service`).
- **Desligamento gracioso (SIGTERM) não está configurado nem provado (#41).** Nenhum
  `application.yml` define o prazo de desligamento; vale o padrão do container (5 s), que o
  Boot 4.1 não expõe. Lido no código do Spring AMQP, sem teste: passado o prazo, o container
  interrompe a thread, e a interrupção na espera do retry vira "retry esgotado" — o
  recoverer manda a mensagem para a DLQ e o container a confirma, em vez de devolvê-la à fila.
