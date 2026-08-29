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

## Comandos

O projeto compila no **JDK 25**, que é para onde o `java` do host resolve (mise). Não é
preciso forçar `JAVA_HOME`.

Até a atualização para o Spring Boot 4.1.1 o build exigia o JDK 21, e a explicação que
circulava — "o Lombok não suporta o 25" — descrevia o sintoma, não a causa. O que mudou
foi o `javac`: o JDK 21 depreciou a descoberta de annotation processors pelo classpath e
o 23 passou a assumir `-proc:none` quando ninguém declara o caminho. O Lombok deixava de
rodar sem dizer nada, e a falha aparecia adiante como `cannot infer type arguments`. O
`annotationProcessorPaths` no pom raiz declara esse caminho — **não o remova**, sob pena
de o erro voltar exatamente com essa cara.

| Objetivo | Comando |
|---|---|
| Build completo (reator) | `mvn -B clean install -DskipTests` |
| Rodar os testes unitários | `mvn -B test` |
| Rodar tudo, incluindo os de integração | `mvn -B verify` |
| Testes de um módulo | `mvn -B test -pl hotel-service` |
| Um teste específico | `mvn -B test -pl hotel-service -Dtest=RoomValidationTest` |
| Um método de teste | `mvn -B test -pl hotel-service -Dtest=RoomValidationTest#nomeDoMetodo` |
| Um teste de integração | `mvn -B verify -pl hotel-service -Dit.test=FlywayMigrationIT` |
| Compilar só o `commons` e dependentes | `mvn -B compile -pl commons -am` |

Não há linter, formatter nem gate de cobertura configurado.

### Testes de integração

Os testes com sufixo `IT` sobem um container do banco pelo Testcontainers e ficam sob o
**failsafe**, não sob o surefire — `mvn test` continua sendo a volta rápida de quem mexe
em domínio, e `mvn verify` é a que exige Docker no host. A separação é só pelo sufixo:
o surefire não coleta `*IT`, o failsafe coleta por padrão.

Cada serviço com banco tem um `AbstractDatabaseIT` que declara o container e um
`application-test.yml` com `hibernate.ddl-auto: validate`. **A asserção de forma mais
forte não está em nenhum método de teste**: é o `validate`. Se o contexto sobe, o
Hibernate já confrontou cada `@Column` das entidades contra as colunas que o Flyway
acabou de criar. Os métodos cobrem o que ele não olha — dados de seed, acentuação,
precisão decimal, chaves e a semântica das `@Query`.

O `AbstractDatabaseIT` é o **único ponto de cada módulo que nomeia o banco**. Trocar de
engine é trocar aquela declaração; nenhuma subclasse menciona MySQL ou PostgreSQL, e é
por isso que elas provam equivalência em vez de provar que um banco funciona.

Essa rede foi escrita **antes** da troca de MySQL por PostgreSQL, e verde contra o banco
antigo. É o que a fez provar equivalência: um teste que nasce junto com o banco novo prova
apenas que o banco novo funciona. Dos 61, só um precisou mudar por motivo não previsto —
`aplicaTodasAsVersoes`, por causa da linha sem versão que o `create-schemas` grava.

Três testes carregam intenção que o nome não entrega, e o Javadoc de cada um explica:

| Teste | O que ele guarda |
|---|---|
| `FlywayMigrationIT#preservaCentavosNoPrecoDoQuarto` (hotel) | Nasceu cobrando `200` para quebrar na migração. Quebrou: `numeric(10, 2)` devolve `199.99` |
| `FlywayMigrationIT#preservaCentavosNoTotalDaReserva` (booking) | O mesmo, com `1235` → `1234.56` |
| `HotelJpaRepositoryIT#achaCidadeSemAcento` | Cobrava um comportamento que o MySQL dava de graça pela colação `utf8mb4_0900_ai_ci`. Continuou verde porque a migração o tornou explícito, não porque sobreviveu sozinho |

A busca sem acento é o caso que mais ensina: `cuiaba` achava `Cuiabá` sem que nenhuma linha
de código pedisse isso. No PostgreSQL ela depende de três peças que precisam continuar
casando — a extensão `unaccent` da `V011`, o `UnaccentFunctionContributor` que declara o
tipo de retorno ao Hibernate, e o `public` no fim do `currentSchema` da URL. Tirar qualquer
uma quebra a busca; sem o contributor, o serviço nem sobe.

**Subir o ambiente** (a partir de `docker/`, com os volumes externos já criados — ver
README): `docker-compose -p hotel-booking-system -f common.yml -f services.yml up -d`.
As quatro imagens saem de um **único `Dockerfile` na raiz**, parametrizado por
`--build-arg MODULE=<servico>`. As camadas estão ordenadas por volatilidade (poms →
`commons/src` → `<servico>/src`), então as três primeiras são idênticas nas quatro
imagens e vêm do cache. Ao adicionar um módulo ao reator, o `COPY` do `pom.xml` dele
precisa entrar no `Dockerfile` — o Maven exige o reactor completo para resolver o parent.

O workflow `.github/workflows/build.yml` roda `mvn verify` no reactor inteiro e publica
no GHCR só o que mudou, com labels OCI de proveniência. `commons` não vira imagem: é
gatilho de reconstrução dos quatro. O que sai do build depende de onde o commit caiu:

| Gatilho | Roda | Publica |
|---|---|---|
| `pull_request` (qualquer origem) | `mvn verify` | — |
| push em `develop` | `mvn verify` | — |
| push em `release/X.Y.Z` ou `hotfix/X.Y.Z` | bump do `<revision>` + `mvn verify` | `X.Y.Z-RC.<run_number>` e `X.Y.Z-RC-latest` |
| push em `master` | `mvn verify` + `git tag X.Y.Z` | `X.Y.Z-<run_number>` e `X.Y.Z-latest` |

A tag imutável (`-<run_number>`) é a que se cita num diagnóstico: ela responde qual
imagem está rodando no homelab. A móvel (`-latest`) existe só para quem não quer
descobrir o número do build, e nunca substitui a primeira.

`.github/workflows/back-merge.yml` abre o PR `master → develop` a cada push na `master`,
para que a correção que entrou por `hotfix/*` não desapareça no próximo corte de release.
Ele não faz o merge — só abre o PR, e reaproveita o que já estiver aberto.

O desenho completo do fluxo está em `docs/diagrams/06-gitflow-pipeline.jpg`.

### Gitflow

Nenhum trabalho nasce na `master` nem na `develop`:

- `feature/*` nasce da `develop` e volta para ela por PR.
- `release/X.Y.Z` nasce da `develop`; `hotfix/X.Y.Z` nasce da `master`. Nas duas, **a
  versão vem do nome da branch** e o pom é ajustado para segui-la — a pipeline commita
  `chore(release): bump da versão para X.Y.Z` na própria branch. O `<revision>` recebe
  apenas o semver; o `run_number` existe só na tag da imagem.
- `release/*` e `hotfix/*` entram na `master` por PR, e a `master` volta para a `develop`
  pelo PR que o `back-merge` abre.
- Ao entrar na `master`, o commit ganha a tag anotada `X.Y.Z` — sem prefixo `v`, e criada
  depois do build e da publicação, nunca antes. Ela é idempotente: um push posterior na
  `master` que não mexa no `<revision>` encontra a tag existente e não tenta recriá-la.

Dois efeitos do `GITHUB_TOKEN` que explicam decisões do workflow: pushes feitos com ele
não disparam novas execuções — é o que impede o bump de entrar em loop, e é por isso que
a imagem RC precisa ser construída na mesma execução que fez o bump. Pela mesma razão, o
PR de back-merge nasce sem checks.

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

Os diagramas do README ficam em `docs/diagrams/`. O `.excalidraw` é a fonte; o `.jpg`
é derivado e precisa ser regerado sempre que a fonte mudar:

```bash
# 1. .excalidraw -> .png (renderer headless da skill excalidraw-diagram)
cd ~/.claude/skills/excalidraw-diagram/references
uv run python render_excalidraw.py <abs-path>/docs/diagrams/NN-nome.excalidraw

# 2. .png -> .jpg reamostrado para 2600px de largura, e descarte do .png
cd <repo>/docs/diagrams
uv run --with pillow python -c "
from PIL import Image; import glob, os
for p in glob.glob('*.png'):
    im = Image.open(p); rgb = Image.new('RGB', im.size, (255,255,255))
    rgb.paste(im, mask=im.split()[-1] if im.mode=='RGBA' else None)
    if rgb.width > 2600: rgb = rgb.resize((2600, round(rgb.height*2600/rgb.width)), Image.LANCZOS)
    rgb.save(p[:-4]+'.jpg', 'JPEG', quality=90, optimize=True, subsampling=0); os.remove(p)
"
```

Só o `.jpg` é versionado — o `.png` é intermediário.

## Arquitetura

### Módulos

- `commons` — building blocks de domínio (`AbstractDomainEntity`, `Money`, ids tipados),
  DTOs de resposta HTTP e, principalmente, **o contrato de eventos entre os serviços**.
- `hotel-service` (8001) — API REST de hotéis/quartos e **coordenador da saga**.
- `booking-service` (8002) — dono da disponibilidade e das reservas por período.
- `payment-service` (8003) — pagamento **fake**; sem banco; falha por sorteio
  (`FAILURE_CHANCE_PERCENTAGE`).
- `customer-service` (8004) — projeção read-model da reserva do cliente + timeline.

Os três serviços com banco dividem uma instância PostgreSQL e têm um **schema** próprio
(`hotel`, `booking`, `customer`), com migrations Flyway em `src/main/resources/db/migration`.
Foi um schema por serviço, e não um banco por serviço, por causa do homelab onde o sistema
roda — a separação de nomes é a mesma, e o custo em processos, não.

### Camadas dentro de cada serviço

O padrão é hexagonal, repetido identicamente nos 4 serviços:

```
core/domain/      entidades, value objects, exceções — sem Spring, sem JPA
core/application/ use cases, handlers de mensagem, mappers, DTOs (records)
core/ports/api/   portas de entrada  (use cases, handlers, mappers)
core/ports/spi/   portas de saída    (repositories, listeners, publishers, queries)
data/db/          entidades JPA, adapters de repositório, mappers de persistência
data/messaging/   listeners e publishers RabbitMQ
application/      Spring: controllers, @Configuration, properties, application service
```

Regras que o código segue e que devem ser mantidas:

- **`core/` não conhece Spring.** Use cases e handlers são POJOs instanciados à mão em
  `*BeanConfiguration` (`HotelBeanConfiguration`, `BookingBeanConfiguration`, …). Só as
  camadas `data/` e `application/` usam estereótipos (`@Component`, `@Configuration`).
- **Mappers são escritos à mão** (`*MapperImpl`). Não há MapStruct.
- O domínio tem entidade e entidade JPA **separadas**, com mapper explícito entre elas
  (`BookingDatabaseMapper`, `HotelDatabaseMapper`, …).
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
   customer). Responde 200 imediatamente com o `reservationOrderId` — o resto é assíncrono.
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
  `TrustedEventPackages` (no `commons`), que os quatro `RabbitMQConfiguration` passam ao
  converter. O `Jackson2JsonMessageConverter` que veio antes confiava em qualquer pacote
  por padrão; o sucessor confia apenas em `java.util` e `java.lang`, e recusa o resto com
  `IllegalArgumentException` **no listener**. A falha não aparece no arranque: os quatro
  serviços sobem saudáveis, o `POST /hotel/booking` responde 200, e a saga simplesmente
  não avança. O casamento é por igualdade de pacote, não por prefixo — **um subpacote novo
  de evento precisa entrar naquela lista**.
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
- `ContratoDeEventosTest`, no `commons`, varre o pacote e prova que cada classe concreta de
  evento é construível. Foi escrito depois que essa falha passou por um `mvn verify` verde:
  ela não aparece no arranque nem nos testes de domínio, só no listener — serviços
  saudáveis, `POST /hotel/booking` respondendo 200 e a saga parada. Evento novo entra na
  cobertura sozinho; não há lista para manter.
- Exchanges, routing keys e filas são declarados em cada serviço via
  `RabbitMQConfiguration` + `@ConfigurationProperties` (`ExchangeProperties`,
  `RoutingKeyProperties`, `QueueProperties`), lidos de `app.rabbitmq.*` no `application.yml`.

## Skills do projeto

- **`registrar-ideia`** (`.claude/skills/registrar-ideia/SKILL.md`) — registra um achado do
  código de 2023 como ideia de post na base `Posts — Refatoração TCC` do Notion. Dispara
  quando o Gabriel diz que algo "daria um post" / "vale um post" / "é um achado", ou quando
  pede para listar e atualizar as ideias já registradas. Requer o MCP do Notion.
