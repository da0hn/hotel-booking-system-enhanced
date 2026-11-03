# AGENTS.md

Instruções para agentes de código trabalhando neste repositório.

## Contexto do projeto

Monorepo Maven multi-módulo que simula o fluxo de reserva de quartos de hotel (estilo
`booking.com`) com 4 microsserviços Spring Boot + 1 módulo compartilhado. Origem: TCC de
Engenharia da Computação (IFMT). Está em fase de **refatoração incremental**: as pendências
conhecidas estão registradas como issues no GitHub, cada uma com critérios de aceite e nota
técnica. Consulte-as antes de propor mudanças estruturais (`gh issue list`).

O `README.md` documenta setup, portas, credenciais e a tabela de exchanges/filas. Não
duplique esse conteúdo aqui.

## Processo de desenvolvimento

Para qualquer alteração relacionada a uma issue, siga este fluxo:

1. Crie a branch a partir de `develop`, usando o número da issue sem inventar ou omitir
   esse identificador:
   - `feature/GH-${numero-da-issue}` para funcionalidades ou alterações de comportamento.
   - `bugfix/GH-${numero-da-issue}` para correções de defeitos.
   - Quando não houver issue associada, use `feature/{slug-da-mudança}` ou
     `bugfix/{slug-da-correção}`, substituindo o trecho entre chaves por um slug curto,
     descritivo e em kebab-case.
2. Faça todos os commits da implementação nessa branch ou em um worktree que tenha essa
   branch selecionada. Antes de cada commit, verifique esse vínculo e não commite na branch
   base.
3. Quando houver issue, inclua, após uma linha em branco, o rodapé
   `Refs: GH-${numero-da-issue}` em cada commit da implementação. Quando não houver issue,
   não inclua esse rodapé.
4. Abra um PR dessa branch para `develop` ao concluir a implementação.

5. Ao implementar uma feature, siga TDD: escreva primeiro um teste que falhe e expresse
   o comportamento esperado, implemente a menor mudança necessária para fazê-lo passar e
   refatore mantendo a suíte verde. Não considere a feature concluída sem testes
   automatizados que cubram o comportamento novo e suas regressões relevantes.

6. A issue é considerada concluída quando o PR é mesclado na `develop`, e não quando a
   mudança chega à `master` numa release. O GitHub só fecha uma issue sozinho pelo `Closes`
   de um PR para a branch padrão (`master`), então o fechamento aqui é manual: `gh issue
   close` com um comentário que liste os commits e o PR. Uma issue guarda-chuva com itens
   ainda sem entrega não fecha por ter tido um PR mesclado: o PR usa `Refs`, e o restante
   vira issues novas antes de a original ser fechada.

Use worktrees principalmente quando vários subagentes implementarem alterações em paralelo.
O worktree é apenas o diretório de trabalho; a branch da issue continua sendo obrigatória.

O agente não deve criar commits manuais diretamente em `develop`, `master`, `release/*` ou
`hotfix/*`. Faça uma exceção somente quando o usuário solicitar explicitamente esse commit.
Commits e tags gerados automaticamente pelos workflows de release não são commits de
implementação do agente e não devem ser reproduzidos manualmente.

### Criar issues

Crie issues com a skill `create-issue`, nunca com `gh issue create` direto, e invoque-a
mesmo quando a issue nascer no meio de outra tarefa. Não copie o formato de uma issue
existente: as mais antigas (como a #3, a #7, a #33 e a #35) seguem um formato legado, com
três seções, tabela de arquivos a modificar, passos de implementação e "Cenários de Teste".
O padrão atual tem exatamente duas seções, `Critérios de Aceite` e `Nota Técnica`, e a #39
o segue. Copiar uma issue antiga reproduz o formato que a skill existe para substituir.

## Comandos

O projeto fixa o `JDK 25.0.2` e o `Maven 3.9.11` em `mise.toml`. Execute `mise install`
na raiz para instalar ou validar o toolchain e mantenha o `mise` ativo no shell; não é
preciso forçar `JAVA_HOME`.

Até a atualização para o Spring Boot 4.1.1 o build exigia o JDK 21, e a explicação que
circulava — "o Lombok não suporta o 25" — descrevia o sintoma, não a causa. O que mudou
foi o `javac`: o JDK 21 depreciou a descoberta de annotation processors pelo classpath e
o 23 passou a assumir `-proc:none` quando ninguém declara o caminho. O Lombok deixava de
rodar sem dizer nada, e a falha aparecia adiante como `cannot infer type arguments`. O
`annotationProcessorPaths` no pom raiz declara esse caminho — **não o remova**, sob pena
de o erro voltar exatamente com essa cara.

O reactor roda em paralelo por padrão: `.mvn/maven.config` fixa `-T 1C`, e o `mvn` local, o
do CI e o do e2e herdam isso. O `Dockerfile` não, porque não copia o `.mvn`. Depois do
`commons` os quatro serviços são independentes, e o `clean verify` caiu de 3 min 09 s para
2 min 02 s numa máquina de 12 núcleos com Docker Desktop (medido em nov/2026, sem o e2e).
O arquivo não aceita comentário, por isso o porquê mora aqui. O custo é que o log intercala
os módulos: para lê-lo em ordem, ao investigar um teste que falha, passe `-T 1`, que a linha
de comando sobrescreve o arquivo.

| Objetivo | Comando |
|---|---|
| Um teste de integração | `mvn -B verify -pl hotel-service -Dit.test=FlywayMigrationIT` |
| Só os testes ponta a ponta (profile `e2e`) | `mvn -B verify -Pe2e -pl e2e-tests -am -Dtest=NoSuchTest -Dit.test='com.hotel.booking.system.e2e.**' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false` |

Não há linter, formatter nem gate de cobertura configurado.

### Testes de integração

Os testes com sufixo `IT` sobem um container do banco pelo Testcontainers e ficam sob o
**failsafe**, não sob o surefire — `mvn test` continua sendo a volta rápida de quem mexe
em domínio, e `mvn verify` é a que exige Docker no host. A separação é só pelo sufixo:
o surefire não coleta `*IT`, o failsafe coleta por padrão.

Cada serviço com banco tem um `AbstractDatabaseIT` que declara o container, conecta com o
usuário restrito do serviço e um `application-test.yml` com `hibernate.ddl-auto: validate`. **A asserção de forma mais
forte não está em nenhum método de teste**: é o `validate`. Se o contexto sobe, o
Hibernate já confrontou cada `@Column` das entidades contra as colunas que o Flyway
acabou de criar. Os métodos cobrem o que ele não olha — dados de seed, acentuação,
precisão decimal, chaves e a semântica das `@Query`.

O `AbstractDatabaseIT` é o **único ponto de cada módulo que nomeia o banco**. Trocar de
engine é trocar aquela declaração; nenhuma subclasse menciona MySQL ou PostgreSQL, e é
por isso que elas provam equivalência em vez de provar que um banco funciona.

A busca sem acento é o caso que mais ensina: `cuiaba` achava `Cuiabá` sem que nenhuma linha
de código pedisse isso. No PostgreSQL ela depende de três peças que precisam continuar
casando — a extensão `unaccent` da `V011`, o `UnaccentFunctionContributor` que declara o
tipo de retorno ao Hibernate, e o `public` no fim do `currentSchema` da URL. Tirar qualquer
uma quebra a busca; sem o contributor, o serviço nem sobe.

### Testes ponta a ponta (`e2e-tests`)

O módulo `e2e-tests` (Spock) sobe PostgreSQL, RabbitMQ e os quatro serviços como processos
filhos e prova que a saga fecha: o `SagaIT` cobre pagamento aprovado, último quarto já
ocupado, `booking-service` derrubado no meio da transação e pagamento recusado; o
`ActuatorExposureIT` e o `HttpErrorContractIT` conferem a fronteira HTTP. **O `mvn verify`
comum não os roda**: o módulo só entra no reactor com o profile `e2e` (comando na tabela de
*Comandos*), por decisão do mantenedor para poupar tempo de CI. Quem mexe no contrato de
eventos, na fronteira transacional ou na regra de disponibilidade o roda antes de abrir o PR;
no CI, é a label `e2e` no pull request ou o `workflow_dispatch` de
`.github/workflows/e2e.yml`. Os cenários, as armadilhas e como acrescentar um teste estão em
`e2e-tests/AGENTS.md`.

### Imagens e pipeline

As quatro imagens saem de um **único `Dockerfile` na raiz**, parametrizado por
`--build-arg MODULE=<servico>`; o passo a passo para subir o ambiente está no README. Ao
adicionar um módulo ao reator, o `COPY` do `pom.xml` dele precisa entrar no `Dockerfile` — o
Maven exige o reactor completo para resolver o parent. O que cada gatilho publica no GHCR
está em `.github/workflows/build.yml`.

### Gitflow

`release/X.Y.Z` nasce da `develop` e `hotfix/X.Y.Z` da `master`; nas duas, **a versão vem do
nome da branch** e a pipeline commita o bump do `<revision>`. A `master` volta para a `develop`
pelo PR que o `back-merge` abre, e o commit que entra na `master` ganha a tag anotada `X.Y.Z`
(sem prefixo `v`). O fluxo completo, as tags de imagem e os efeitos do `GITHUB_TOKEN` estão em
`.github/AGENTS.md`; o desenho está em `docs/diagrams/06-gitflow-pipeline.jpg`.

### Banco de dados em desenvolvimento

Para rodar um serviço isolado na IDE não é preciso configurar nada: os defaults do
`application.yml` apontam para `localhost:5442`, que é a porta que o compose **publica** — não
a 5432, que é a que o PostgreSQL escuta dentro da rede. O deslocamento é o mesmo que os
containers de MySQL faziam com 3311/3312/3313, e pela mesma razão: a porta default costuma já
estar ocupada por outro banco na máquina de quem desenvolve.

Os três serviços com banco dividem uma instância e se separam por **schema**. O nome do
schema aparece em três lugares por serviço, e cada um governa um subsistema diferente:
`spring.flyway.schemas` diz onde criar as tabelas, `hibernate.default_schema` qualifica o
SQL gerado e o validador do `ddl-auto`, e o `currentSchema` da URL resolve o `search_path`
de quem não passa por nenhum dos dois — as consultas nativas. Os três leem a mesma variável
de ambiente (`HOTEL_DB_SCHEMA` e afins), então a fonte da verdade continua sendo uma.

O `default_schema` não é opcional neste layout: sem ele o validador procura a tabela sem
qualificar o schema, e `room` existe tanto em `hotel` quanto em `booking` dentro do mesmo
banco.

### Diagramas

Os diagramas do README ficam em `docs/diagrams/`: o `.excalidraw` é a fonte e o `.jpg` é
derivado, regerado a cada mudança da fonte (o passo a passo está em `docs/diagrams/AGENTS.md`).
O `05-data-model-antes-mysql` é **deliberado**: ele registra o desenho de três bancos que a
migração desfez, e por isso não se regenera, não se atualiza e não se apaga.

## Arquitetura

### Módulos

- `hotel-service` coordena a saga; `booking-service` é dono da disponibilidade e das reservas;
  `customer-service` é read-model; `payment-service` é fake, sem banco, e falha por sorteio
  (`FAILURE_CHANCE_PERCENTAGE`); `commons` guarda os building blocks de domínio e, principalmente,
  **o contrato de eventos entre os serviços**. Portas e papéis estão no README.
- `e2e-tests` não é serviço, não vira imagem e nada depende dele; só existe no reactor com o
  profile `e2e`.

Os três serviços com banco dividem uma instância PostgreSQL e têm um **schema** próprio
(`hotel`, `booking`, `customer`), com migrations Flyway em `src/main/resources/db/migration`.

O padrão é *database per service*, e a redução para um schema por serviço é consequência da
limitação de recurso do homelab, não uma mudança de desenho: **cada serviço continua dono
exclusivo do seu dado, e nenhum outro o alcança — nem por consulta, nem por constraint.**

Daí uma regra que o layout compartilhado deixou de proteger sozinho: **nunca declare uma
chave estrangeira atravessando schemas.** Colunas como `booking.booking.customer_id` e
`booking.room.hotel_id` guardam ids de domínios alheios de propósito; a ausência de FK ali é
o desenho, não uma lacuna a corrigir. Uma FK faria o serviço depender estruturalmente do
domínio de outro, que é exatamente a dependência que a saga existe para evitar — e acoplaria
os deploys e as migrations dos dois.

Enquanto eram três instâncias de MySQL separadas, essa violação era impossível de escrever.
Numa instância só ela compila, e por isso o isolamento passou a ser sustentado por privilégio:
**cada serviço conecta com o próprio usuário**, `user_hotel_service`, `user_booking_service` e
`user_customer_service`, criados por `docker/scripts/01-create-service-roles.sql`.

Não há `REVOKE` nesse arquivo, e não é esquecimento. Um schema no PostgreSQL pertence a quem o
criou, e nenhum outro papel recebe `USAGE` nele por padrão — como cada serviço cria o seu pelo
Flyway, com o próprio usuário, a negação já é o comportamento default. O que o script faz é só
criar os papéis, tirar do `PUBLIC` o acesso ao banco e conceder `create` a cada um, que é o
privilégio de que o Flyway precisa para criar o schema **e** o que o torna dono dele. Se algum
dia o schema passar a nascer de outro papel, o isolamento some sem que uma linha de
configuração mude; `IsolacaoEntreSchemasIT` existe para acusar isso.

Duas consequências práticas disso:

- **Os testes de integração conectam com o usuário do serviço, não com o dono do container.**
  É o que faz uma migration que exija privilégio demais falhar no `mvn verify`, e não só no
  ambiente onde ninguém está olhando. Foi assim que a V011 mudou: `create extension ... with
  schema public` exige `CREATE` no `public`, que o `PUBLIC` perdeu no PostgreSQL 15 — sem
  `with schema`, a extensão nasce no schema do próprio serviço, que ele possui.
- **O `@ServiceConnection` não é usado.** Ele deriva usuário e senha do container, e o que se
  quer é justamente conectar como outro; o `AbstractDatabaseIT` monta o `DataSource` com
  `@DynamicPropertySource` e monta no container o mesmo arquivo de papéis que o compose usa,
  para que os dois não divirjam.

Os dois desenhos estão lado a lado em `docs/diagrams/05-data-model-antes-mysql.jpg` e
`-depois-postgres.jpg`.

### Camadas dentro de cada serviço

O padrão é hexagonal, repetido identicamente nos 4 serviços.

Regras que o código segue e que devem ser mantidas:

- **`core/` não depende das camadas `infrastructure/` nem da camada externa `application/`.** O
  `core/domain` pode usar anotações declarativas do Spring quando isso for conveniente,
  mas não deve importar classes da camada `infrastructure`. Implementações de casos de uso usam
  `@UseCase`, mappers usam `@Mapper` e serviços de domínio usam `@DomainService`; todas são
  anotações do `commons` meta-anotadas com `@Component` e recebem suas dependências por
  construtor. Listeners e publishers de mensageria usam, respectivamente, `@Listener` e
  `@Publisher`. Handlers e componentes de configuração sem estereótipo próprio continuam
  registrados em `infrastructure/configuration/*BeanConfiguration`
  (`HotelBeanConfiguration`, `BookingBeanConfiguration`, …).
- **Fronteiras transacionais ficam nos adaptadores de entrada.** Use `@Transactional` nos
  `*ApplicationServiceImpl` e nos listeners RabbitMQ que iniciam operações de escrita ou
  coordenam leitura e escrita. Use `@Transactional(readOnly = true)` nas consultas. Não
  aplique `@Transactional` diretamente nos use cases nem nos adapters de repositório;
  o listener deve propagar a exceção para permitir o rollback.
- **A disponibilidade do quarto depende de um lock, e o lock depende da transação do
  listener.** O `VerifyRoomAvailability` começa por `RoomRepository.lockAllByRoomId`
  (`SELECT ... FOR UPDATE`, em ordem de id) e só então lê a ocupação; o lock dura até o commit do
  `BookingRoomRequestedRabbitMQListener`, que também grava a reserva. Transação sozinha não
  basta: no `READ COMMITTED` duas reservas do último quarto leem a mesma ocupação e passam as
  duas, e o `RoomAvailabilityConcurrencyIT` prova isso contra o PostgreSQL. Tirar o
  `@Transactional` do listener não passa em silêncio — a consulta com `@Lock` lança
  `TransactionRequiredException`. A ocupação **soma `booking_room.quantity`**; contar linhas
  trata 3 unidades como 1. Como todo listener roda com no mínimo 3 consumidores (seção
  *Falha no consumidor*), a corrida existe mesmo com uma só réplica; o e2e não a reproduz
  porque o `SagaIT` envia um pedido por vez.
- **Mappers são escritos à mão** (`*MapperImpl`). Não há MapStruct.
- O domínio tem entidade e entidade JPA **separadas**, com mapper explícito entre elas
  (`BookingDatabaseMapper`, `HotelDatabaseMapper`, …).
- **`Money` tem duas escalas, e escolher a errada não dá erro nenhum.** `getValue()` devolve
  4 casas: é o que vai para as colunas `numeric(19, 4)` e para o contrato de eventos.
  `getPresentationValue()` devolve 2 e serve **só** à resposta HTTP — há exatamente dois usos,
  em `HotelUseCaseMapperImpl` e `CustomerUseCaseMapperImpl`, e nada que ainda vá ser somado ou
  multiplicado pode passar por ele. Arredondar cada parcela antes de somar não dá o mesmo total
  que somar e arredondar no fim, e a troca não produz exceção: sai um número plausível, errado
  por centavos. Um caminho novo de saída ao usuário usa o segundo; qualquer outro, o primeiro.
- Estilo: indentação de 2 espaços, `final` em parâmetros e campos, `this.` explícito em
  todo acesso a membro, `var` para locais.
- **A explicação vai no Javadoc, não em comentário inline.** Se o que se quer dizer
  descreve o que a classe ou o método é, faz ou pressupõe, o lugar é o Javadoc do
  elemento — não um `//` solto acima da assinatura nem no meio do corpo. Comentário
  inline fica reservado ao que **não cabe** no Javadoc: um contexto preso a uma linha
  específica, que quem lê aquele trecho precisa saber ali e que se perderia descrito de
  fora. Na dúvida, é Javadoc.
- A mesma economia vale para `pom.xml` e para os workflows: comente o que surpreende, não
  o que o próprio arquivo já diz.

### Fluxo da saga (o que é preciso entender antes de mexer)

O `hotel-service` é o ponto de decisão: ele reage a cada resposta e decide o próximo passo.
Os outros três serviços só reagem ao que chega na sua fila.

1. `POST /hotel-service/hotel/booking` → `BookingRoomRequestUseCaseImpl` valida quartos e
   capacidade contra o banco do hotel, gera um `reservationOrderId` e publica
   `BookingRoomRequestedEvent` (para o booking) + `CustomerBookingInitiatedEvent` (para o
   customer). Responde 200 com o `reservationOrderId` assim que o broker confirma as duas
   publicações — o resto é assíncrono. Se uma publicação falha, o `HotelExceptionHandler`
   responde 503 com `hotel.booking.temporarily.unavailable`. As duas publicações **não são
   atômicas**: se a segunda falha, o booking já recebeu a primeira. Só o Outbox (#35) fecha
   essa janela.
2. `booking-service` consome, valida disponibilidade no período (`VerifyRoomAvailability`),
   grava a reserva como `PENDING` e responde `BookingRoomPendingEvent` ou
   `BookingRoomFailedEvent`.
3. `hotel-service` (`BookingRoomResponseHandlerImpl`) recebe `Pending` → notifica o customer
   (`AWAITING_PAYMENT`) e publica `PaymentRequestedEvent`.
4. `payment-service` sorteia sucesso/falha e responde `PaymentCompletedEvent` /
   `PaymentFailedEvent`.
5. `hotel-service` (`PaymentResponseHandlerImpl`) propaga para o customer e manda o booking
   mudar o status (`BookingRoomPaymentCompleted` → `CONFIRMED`, ou `...Failed` → `CANCELED`).
6. `customer-service` grava cada transição na timeline; o cliente consulta em
   `GET /customer-service/customers/{customerId}/reservation-order/{reservationOrderId}`.

Consequência prática: **para adicionar um passo na saga, mexe-se em três lugares** — o
evento no `commons`, o publisher/listener nos serviços das duas pontas, e o `switch` do
handler correspondente no `hotel-service`.

### Contrato de mensagens

- Eventos vivem em `commons/core/domain/event/`, organizados em hierarquias `sealed`
  (`BookingRoomResponseEvent`, `PaymentResponseEvent`, `CustomerBookingStatusUpdatedEvent`).
  Os handlers despacham com `switch` sobre pattern matching de tipo.
- A desserialização usa `JacksonJsonMessageConverter` (Jackson 3), que grava o **FQCN da
  classe no header `__TypeId__`**. Isso significa que **mover ou renomear uma classe de
  evento no `commons` quebra a comunicação entre serviços em versões diferentes** —
  mensagens já na fila deixam de ser desserializáveis.
- O FQCN que chega no `__TypeId__` só é instanciado se o **pacote** dele estiver em
  `TrustedEventPackages` (no `commons`), que o `EventMessageConverter` (também no
  `commons`) aplica e que os quatro `RabbitMQConfiguration` instanciam. O
  `Jackson2JsonMessageConverter` que veio antes confiava em qualquer pacote por padrão; o
  sucessor confia apenas em `java.util` e `java.lang`, e recusa o resto com
  `IllegalArgumentException` **no listener**. A falha não aparece no arranque: os quatro
  serviços sobem saudáveis, o `POST /hotel/booking` responde 200, e a saga simplesmente
  não avança. O casamento é por igualdade de pacote, não por prefixo — **um subpacote novo
  de evento precisa entrar naquela lista**. `UnsupportedEventTypeException` mora em
  `core/domain/exception/` de propósito: dentro do pacote de eventos ela seria varrida pelo
  `EventContractTest` e teria de constar em `TrustedEventPackages`.
- **Toda classe de evento com `@SuperBuilder` precisa de `@Jacksonized`.** O Jackson só
  constrói uma classe que enxergue **um** creator, e o `@SuperBuilder` cria um segundo (o
  construtor que recebe o builder). Sem a anotação ele recusa a mensagem **no listener**: os
  serviços sobem saudáveis e a saga para. O `@Jacksonized` depende de
  `lombok.jacksonized.jacksonVersion += 3` no `lombok.config` da raiz (com `=` no lugar de `+=`
  a chave é ignorada em silêncio), e o `EventContractTest` prova que cada evento é construível.
  Os detalhes estão em `commons/AGENTS.md`.
- **Publisher falha alto; nunca engole a exceção.** Todo publisher estende
  `AbstractRabbitPublisher` (`commons/infrastructure/messaging`), que envia com
  `CorrelationData`, espera o confirm do broker e trata o return — a mensagem que nenhuma fila
  recebeu — como falha, lançando `MessagePublishException`. Um `catch` que só loga recria o
  defeito da #3: a saga trava sem sinal e o `POST` responde 200 com o broker fora. A regra
  `publishersExtendAbstractRabbitPublisher` do ArchUnit de cada serviço recusa publisher que
  não estenda a base.
- A garantia de entrega depende de **três chaves no `application.yml` de quem publica**:
  `publisher-confirm-type: correlated`, `publisher-returns: true` e `template.mandatory: true`.
  Sem `mandatory` o broker confirma a mensagem que descartou por falta de fila, e o publisher
  a dá como entregue. `PublisherConfirmsConfigurationTest` guarda as chaves nos três serviços
  que publicam, e `PublisherDeliveryIT` prova o comportamento contra um RabbitMQ real.
- A exceção sobe pelo listener e desfaz a transação. O retry com DLQ (seção seguinte) impede o
  loop de reentrega, mas até a #7 (idempotência) uma reentrega repete o efeito já gravado.
- Exchanges, routing keys e filas são declarados em cada serviço via
  `RabbitMQConfiguration` + `@ConfigurationProperties` (`ExchangeProperties`,
  `RoutingKeyProperties`, `QueueProperties`), lidos de `app.rabbitmq.*` no `application.yml`.

### Falha no consumidor: retry e DLQ

Uma exceção no listener não devolve mais a mensagem ao início da fila. O desenho vive em
`commons/infrastructure/messaging/` e cada `RabbitMQConfiguration` o importa
(`DeadLetterRetryConfiguration`):

- **Toda fila de negócio nasce por `DeadLetterTopology.queue(...)`**, nunca por
  `new Queue(...)`. Ela declara `x-dead-letter-exchange` e `x-dead-letter-routing-key`, e o
  `Declarables` do serviço cria o DLX global `exchange.dead-letter` e a `<fila>.dlq`. Cada
  serviço redeclara as filas que não consome (para publicar nelas), então os argumentos
  precisam sair do **mesmo helper**: dois serviços declarando a mesma fila com argumentos
  diferentes dão `PRECONDITION_FAILED` no segundo a subir.
- **Os argumentos de uma fila existente não mudam.** O broker recusa a redeclaração de uma
  fila com `x-dead-letter-*` diferente dos que ela já tem (`PRECONDITION_FAILED`); o remédio
  é apagar a fila antes, e as mensagens em voo se perdem. Por isso o README documenta a
  remoção das seis filas no deploy que introduziu as DLQs.
- **Todo listener consome com no mínimo 3 threads**: `spring.rabbitmq.listener.simple.concurrency: 3`
  no `application.yml` de cada serviço, e o `ListenerConcurrencyTest` de cada um o guarda lendo o
  arquivo real. Sem a chave o Spring AMQP usa 1 consumidor por listener. A convenção tem um
  preço que quem escreve um handler precisa conhecer: **a fila deixa de ser processada em
  ordem**, e o retry deixa de segurar as mensagens seguintes — enquanto uma espera o backoff, as
  outras andam. Todo handler que lê, altera e grava o mesmo agregado tem de ser seguro sob
  concorrência. O `booking-service` já é, pelo lock do quarto; o `customer-service` ainda não
  (`UpdateCustomerBookingStatusUseCaseImpl` lê o pedido e grava sem lock nem `@Version`, e o
  `current_status` fica com o de quem commita por último: issue #53).
- **O retry vive em `spring.rabbitmq.listener.simple.retry.*`** (`max-retries: 2`, backoff
  exponencial de 1s a 10s). O Boot 4 **ignora em silêncio** chave que não conhece, como
  `max-attempts`: o `DeadLetterConfigurationTest` de cada serviço lê o `application.yml` real
  para provar que o binding pegou. Esgotadas as tentativas, a mensagem vai para `<fila>.dlq`.
- **Erro determinístico não é retentado**: `MessageConversionException` (JSON malformado,
  classe inexistente, **pacote fora do `TrustedEventPackages`**) e
  `UnsupportedEventTypeException` vão direto para a DLQ. O jitter, os headers da DLQ, a morte do
  processo no meio do consumo e o SIGTERM estão em `commons/AGENTS.md`.

### Erros HTTP

Todo erro HTTP sai no envelope do `ApiErrorResponse` (`success: false`, `message`, `data`,
`time`), e o `message` é **uma chave de `ApplicationMessage`**, nunca texto livre nem a mensagem
de uma exceção que não seja de domínio. A chave é o contrato: quem consome a API (o
`HttpErrorContractIT`, a collection do Postman) casa por ela, e o texto para o usuário final é
problema de quem apresenta.

Cada serviço com controller tem um `@RestControllerAdvice` em `application/web/advice` que
estende o `GlobalExceptionHandler` do `commons` (`HotelExceptionHandler` e
`CustomerExceptionHandler`); o `booking-service` e o `payment-service` não têm controller e,
se ganharem um, o advice nasce no mesmo molde. Dentro do advice só entram as exceções do
domínio do serviço, por `this.reject(status, exception)`.

| Situação | Status | `message` |
|---|---|---|
| Cliente ou ordem de reserva da URL não existem | 404 | a chave da exceção |
| Regra de domínio violada (`HotelDomainException`, `CustomerDomainException`) | 422 | a chave da exceção |
| Campos de entrada recusados pela validação | 400 | `request.invalid`, com `data` = lista de `{field, message}` |
| Corpo ilegível, método não suportado, rota inexistente | o do Spring (400, 405, 404) | `request.invalid` |
| Falha ao publicar o evento (`MessagePublishException`) | 503 | `hotel.booking.temporarily.unavailable` |
| Qualquer outra exceção | 500 | `internal.server.error`, sem detalhe |

O que não se deduz do código:

- **O recurso citado no corpo e inexistente é 422, não 404.** O 404 diz que o recurso da URL não
  existe. O `hotel-service` nunca endereça um quarto pela URL; ele o recebe no corpo de
  `POST /hotel/booking`, então `hotel.room.not.found` é uma regra violada.
- **A base estende `ResponseEntityExceptionHandler`, e não só declara `@ExceptionHandler(Exception.class)`.**
  Um catch-all sozinho captura o `NoResourceFoundException` e promove a 500 o 404 de uma rota
  que não existe, como o `/actuator/env`, que o `ActuatorExposureIT` exige 404. Handler novo
  de erro de framework sobrescreve o método da base; não se cria um segundo catch-all.
- **O `handleExceptionInternal` troca o `ProblemDetail` pelo envelope.** No Spring 7 o
  `handleHttpMessageNotReadable` já chega ali com um `ProblemDetail` montado como `body`, e sem a
  troca o cliente recebe `application/problem+json` fora do envelope. Os 404 e 405 chegam com
  `body == null`, e a troca vale para eles também.
- **4xx loga em WARN, sem stacktrace; 5xx loga em ERROR, com stacktrace.** O stacktrace vai só
  para o log, nunca para o corpo. A consulta repetida de uma ordem que o `customer-service` ainda
  não processou gera 404 em sequência, e um stacktrace por tentativa enterraria o log.
- **Dois limites conhecidos.** Nenhum controller usa `@Valid`, embora o `BookingRoomInput`
  declare `@NotEmpty` e `@Min`: as restrições existem e nunca são avaliadas na fronteira HTTP.
  O 400 com lista de campos é, por isso, preventivo, provado por uma
  `MethodArgumentNotValidException` construída e não por um request real, até a #10 ligar o
  `@Valid`. Quem a implementar encontra o item do erro como `{field, message}`, em que
  `message` é a chave declarada na restrição, e não o `{field, detail}` com texto livre do
  exemplo daquela issue. E um id malformado no path (`UUID.fromString` lança
  `IllegalArgumentException`) continua respondendo 500.

### Actuator

- Cada serviço expõe pela web só `health` e `info`, e o `health` não mostra detalhes
  (`management.endpoint.health.show-details: never`, explícito). O `include: "*"` anterior
  entregava `/actuator/env` (variáveis de ambiente, com as senhas do banco e do RabbitMQ),
  `configprops`, `beans`, `mappings`, `threaddump`, `metrics` e `loggers` a quem alcançasse as
  portas 8001–8004, e não há Spring Security para barrar (issue #12).
- **O `heapdump` não vazava nesta versão.** A issue o cita, mas no Spring Boot 4.1.1 ele é
  `@WebEndpoint(defaultAccess = Access.NONE)`: nem o `*` o expõe, e ele só aparece se alguém
  ligar `management.endpoint.heapdump.access`. O `ActuatorExposureIT` o mantém na lista de
  negados como guarda, mas ele já respondia 404 antes da correção.
- O corpo do `/actuator/health` não é `{"status":"UP"}` puro: o Boot 4 acrescenta
  `"groups":["liveness","readiness"]`, que só nomeia grupos. O que não pode aparecer é
  `components`/`details`, e é isso que o e2e confere.
- **Liberar outro endpoint é uma decisão, não um ajuste.** `metrics` e `prometheus` só entram
  junto com uma coleta que os justifique — hoje não há registry do Micrometer. Quem liberar
  um endpoint muda o `ActuatorExposureConfigTest` de cada serviço no mesmo commit; sem isso o
  `mvn verify` falha. O `ActuatorExposureIT` (e2e) confere o resultado visto de fora.
- O Actuator vem do `pom.xml` raiz e vale para todos os módulos, então o que decide o que
  aparece é só a exposição: é por isso que o 404 de `/actuator/env` prova a configuração, e
  não a ausência do endpoint.
- O `ServiceProcess` do e2e espera `/actuator/health` com 200 e `"UP"`; restringir a
  exposição a `health` preserva esse contrato. Mover o Actuator para outra porta
  (`management.server.port`) ficou de fora da issue #12 de propósito: é uma decisão de
  deploy, e o e2e e qualquer healthcheck teriam de apontar para a outra porta.
