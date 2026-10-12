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

| Objetivo | Comando |
|---|---|
| Build completo (reator) | `mvn -B clean install -DskipTests` |
| Rodar os testes unitários | `mvn -B test` |
| Rodar tudo, incluindo os de integração | `mvn -B verify` |
| Testes de um módulo | `mvn -B test -pl hotel-service` |
| Um teste específico | `mvn -B test -pl hotel-service -Dtest=RoomValidationTest` |
| Um método de teste | `mvn -B test -pl hotel-service -Dtest=RoomValidationTest#nomeDoMetodo` |
| Um teste de integração | `mvn -B verify -pl hotel-service -Dit.test=FlywayMigrationIT` |
| Só os testes ponta a ponta (profile `e2e`) | `mvn -B verify -Pe2e -pl e2e-tests -am -Dtest=NoSuchTest -Dit.test='com.hotel.booking.system.e2e.**' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false` |
| Compilar só o `commons` e dependentes | `mvn -B compile -pl commons -am` |

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

Essa rede foi escrita **antes** da troca de MySQL por PostgreSQL, e verde contra o banco
antigo. É o que a fez provar equivalência: um teste que nasce junto com o banco novo prova
apenas que o banco novo funciona. Dos 61, só um precisou mudar por motivo não previsto —
`aplicaTodasAsVersoes`, por causa da linha sem versão que o `create-schemas` grava.

Três testes carregam intenção que o nome não entrega, e o Javadoc de cada um explica:

| Teste | O que ele guarda |
|---|---|
| `FlywayMigrationIT#preservaCentavosNoPrecoDoQuarto` (hotel) | Nasceu cobrando `200` para quebrar na migração. Quebrou: a escala 2 devolve `199.99` |
| `FlywayMigrationIT#preservaCentavosNoTotalDaReserva` (booking) | O mesmo, com `1235` → `1234.56` |
| `FlywayMigrationIT#preservaAEscalaDeCalculo*` (hotel, booking, customer) | O par dos dois acima, e a razão de existirem separados: `199.99` atravessa `numeric(10, 2)` intacto, então aqueles ficariam verdes mesmo se a V012/V007/V008 não tivessem rodado. Só um valor que precise da quarta casa distingue as duas escalas |
| `HotelJpaRepositoryIT#achaCidadeSemAcento` | Cobrava um comportamento que o MySQL dava de graça pela colação `utf8mb4_0900_ai_ci`. Continuou verde porque a migração o tornou explícito, não porque sobreviveu sozinho |

A busca sem acento é o caso que mais ensina: `cuiaba` achava `Cuiabá` sem que nenhuma linha
de código pedisse isso. No PostgreSQL ela depende de três peças que precisam continuar
casando — a extensão `unaccent` da `V011`, o `UnaccentFunctionContributor` que declara o
tipo de retorno ao Hibernate, e o `public` no fim do `currentSchema` da URL. Tirar qualquer
uma quebra a busca; sem o contributor, o serviço nem sobe.

### Testes ponta a ponta (`e2e-tests`)

O módulo `e2e-tests` reúne os testes que sobem o sistema inteiro: PostgreSQL e RabbitMQ pelo
Testcontainers e `hotel`, `booking`, `customer` e `payment` **como processos filhos**. O
`E2eEnvironment` faz essa subida e é a base de qualquer teste novo do módulo; os testes são
`Specification`s do Spock em `src/test/groovy/com/hotel/booking/system/e2e/`.

Hoje há dois. O `SagaIT` é o único teste que prova que as quatro pontas se entendem. Ele
dispara `POST /hotel/booking` no `hotel-service` e lê o desfecho no `customer-service` e nos
bancos. Os quatro cenários são pagamento aprovado (`RESERVED`), último quarto já ocupado
(`RESERVATION_FAILED`, sem gravar a reserva), `booking-service` derrubado com `kill` no meio
da transação (a saga retoma ao reiniciar, com uma única reserva) e pagamento recusado
(`PAYMENT_FAILED`, reserva `CANCELED`). É a rede de quem mexe no contrato de eventos, na
fronteira transacional ou na regra de disponibilidade: um evento movido de pacote, um pacote
fora do `TrustedEventPackages` ou um listener sem `@Transactional` deixam os quatro serviços
saudáveis e a saga parada, e nenhum teste de unidade enxerga isso.

O outro, o `ActuatorExposureIT`, não toca na saga: bate nos quatro serviços e confere o que o
Actuator mostra por fora (ver *Actuator*, na seção de arquitetura). Por ser uma
`Specification` à parte, ele paga um segundo arranque do ambiente inteiro.

**O `mvn verify` comum não roda os testes ponta a ponta.** O módulo só entra no reactor com o
profile `e2e`, por decisão do mantenedor para poupar tempo de CI (medido em out/2026: ~1,5
min no reactor e ~1,5 min no `SagaIT`, bem mais na máquina local). Quem mexe no que a saga
protege roda o comando da tabela antes de abrir o PR. No CI há dois caminhos, ambos no
workflow `.github/workflows/e2e.yml`: a **label `e2e`** num pull request, que roda a
partir do branch do PR, e o **`workflow_dispatch`**, cujo botão só aparece depois de o
arquivo estar na `master`. Falhou, o artefato `e2e-logs` traz o log inteiro de cada serviço.

**Para acrescentar um teste ponta a ponta**, crie outra `Specification` com sufixo `IT` no
pacote `com.hotel.booking.system.e2e` e suba o ambiente com `E2eEnvironment.start()` em
`setupSpec()`. O filtro do comando da tabela e do workflow
(`-Dit.test='com.hotel.booking.system.e2e.**'`) o pega sem mudança. Cada `Specification` sobe
o próprio ambiente, então agrupe cenários na mesma classe quando o custo do arranque dos
quatro serviços pesar.

O que não se deduz do código:

- **Processos filhos, e não contextos Spring numa JVM só.** Os quatro `application.yml` têm o
  mesmo nome e colidem no classpath. `ServiceProcess` executa `java -jar` do jar que o
  `package` de cada serviço gerou e espera o `/actuator/health`; o log de cada um vai para
  `e2e-tests/target/e2e-logs/` e a cauda dos quatro é anexada à mensagem quando um cenário
  estoura o tempo.
- **Dependências `pom` com `<exclusion>*:*</exclusion>`.** Os jars dos serviços não são
  biblioteca para o teste: a dependência existe só para criar a aresta no grafo do reactor.
  Sem ela, `-Pe2e -pl e2e-tests -am` não traria os quatro serviços e uma build paralela
  (`-T`) poderia rodar o teste antes de os jars existirem. Sem o `-am`, o `ServiceProcess`
  falha com "jar não encontrado" quando os `target/` estão vazios. Sem o `-Pe2e`, o Maven
  responde que não encontra o projeto `e2e-tests`.
- **Um único reinício.** O `payment-service` sorteia o resultado no arranque a partir de
  `FAILURE_CHANCE_PERCENTAGE`. Os cenários rodam na ordem de declaração (o padrão do Spock) e
  `usePaymentFailureChance` só reinicia o serviço quando o valor muda: `0` nos três
  primeiros, `100` no último. Não há `@Stepwise` de propósito: cada cenário tem o próprio
  quarto, e se o primeiro falhar os outros ainda dizem se o resto da saga funciona. O
  custo é que uma saga quebrada leva ~6 min para falhar (4 cenários × 90 s de espera).
- **A morte do consumidor se provoca com um lock, não com cronometragem.** O cenário do
  `booking-service` morto trava `booking.booking` com `lock table ... access exclusive` numa
  conexão do dono do container e espera, em `pg_stat_activity`, o serviço parar num `Lock`:
  só então a mensagem está recebida, sem ack e dentro da transação, e o `kill` é seguro. O
  `ServiceProcess.kill()` encerra sem aviso (`destroyForcibly`); o `stop()` pede o
  encerramento e espera. A janela entre o commit e o ack, que repetiria o efeito, não tem
  gancho para ser provocada daqui: ela é coberta no `DeadLetterRetryIT` e depende da #7.
- **Spock em Groovy; os helpers em Java.** Os testes são `Specification`s em
  `src/test/groovy`; `E2eEnvironment` e `ServiceProcess` ficam em `src/test/java`, e o
  `gmavenplus-plugin` compila o Groovy depois do Java, então um enxerga o outro. O BOM do
  Spring Boot gerencia o Groovy (5.0.8) mas não o Spock, cuja versão está em `spock.version`
  no `pom.xml` do módulo. O Spock 2.5 foi compilado contra o JUnit Platform 1.14 e roda aqui
  sobre o 6.0.3 do BOM, no JDK 25. Dentro de `PollingConditions.eventually { }` o Spock trata
  **cada comando como condição**, até em método auxiliar: uma atribuição solta quebra a
  compilação ("Expected a condition, but found an assignment"). Por isso `awaitStatus` guarda
  o último estado visto numa atribuição dentro do `assert`.
- **O quarto do cenário 2 é semeado pelo teste.** Nenhum quarto das migrations de seed
  permite esgotar o estoque com uma reserva. Hotel e booking guardam réplicas separadas do
  quarto (issue #4), então a semente grava nos dois bancos.

`VerifyRoomAvailabilityTest` (booking) segue outra política: quatro métodos estão `@Disabled`
porque descrevem comportamento **correto** que o código de hoje não tem (issue #5: o `==` no
lugar de `>=`, a contagem de linhas no lugar de `quantity` e o pedido que ignora a
quantidade). O CI roda `mvn verify` em todo pull request e não pode ficar vermelho, mas o
teste não pode ser apagado: ao corrigir a issue #5, remova o `@Disabled` — eles são a
especificação da correção.

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

| Gatilho                                   | Roda                                | Publica                                     |
|-------------------------------------------|-------------------------------------|---------------------------------------------|
| `pull_request` (qualquer origem)          | `mvn verify`                        | —                                           |
| push em `develop`                         | `mvn verify`                        | —                                           |
| push em `release/X.Y.Z` ou `hotfix/X.Y.Z` | bump do `<revision>` + `mvn verify` | `X.Y.Z-RC.<run_number>` e `X.Y.Z-RC-latest` |
| push em `master`                          | `mvn verify` + `git tag X.Y.Z`      | `X.Y.Z-<run_number>` e `X.Y.Z-latest`       |

A tag imutável (`-<run_number>`) é a que se cita num diagnóstico: ela responde qual
imagem está rodando no homelab. A móvel (`-latest`) existe só para quem não quer
descobrir o número do build, e nunca substitui a primeira.

`.github/workflows/back-merge.yml` abre o PR `master → develop` a cada push na `master`,
para que a correção que entrou por `hotfix/*` não desapareça no próximo corte de release.
Ele não faz o merge — só abre o PR, e reaproveita o que já estiver aberto.

O desenho completo do fluxo está em `docs/diagrams/06-gitflow-pipeline.jpg`.

### Gitflow

- O fluxo normal de branches de trabalho e PRs está definido em `Processo de desenvolvimento`.
- `release/X.Y.Z` nasce da `develop`; `hotfix/X.Y.Z` nasce da `master`. Essas branches são
  exceções do fluxo normal e servem ao processo de release. Nas duas, **a versão vem do
  nome da branch** e o pom é ajustado para segui-la — a pipeline commita
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

O `05-data-model` existe em duas versões, `-antes-mysql` e `-depois-postgres`, e a
primeira é **deliberada**: ela registra o desenho de três bancos separados que a migração
desfez. Não a regenere, não a "atualize" e não a apague — o que ela documenta é justamente
o estado que não existe mais. Quando uma mudança estrutural merecer o mesmo tratamento, o
par segue esse nome; nos outros casos o diagrama é editado no lugar.

## Arquitetura

### Módulos

- `commons` — building blocks de domínio (`AbstractDomainEntity`, `Money`, ids tipados),
  DTOs de resposta HTTP e, principalmente, **o contrato de eventos entre os serviços**.
- `hotel-service` (8001) — API REST de hotéis/quartos e **coordenador da saga**.
- `booking-service` (8002) — dono da disponibilidade e das reservas por período.
- `payment-service` (8003) — pagamento **fake**; sem banco; falha por sorteio
  (`FAILURE_CHANCE_PERCENTAGE`).
- `customer-service` (8004) — projeção read-model da reserva do cliente + timeline.
- `e2e-tests` — só testes de ponta a ponta, em Spock: sobem os quatro serviços e exercitam o
  sistema inteiro. Hoje só o `SagaIT`, que prova a saga. Não é serviço, não vira imagem e nada
  depende dele. Só existe no reactor com o profile `e2e` (ver "Testes ponta a ponta").

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

O padrão é hexagonal, repetido identicamente nos 4 serviços:

```
core/domain/      entidades, value objects, exceções — sem dependência da camada `infrastructure/`
core/application/ use cases, handlers de mensagem, mappers, DTOs (records)
core/ports/api/   portas de entrada  (use cases, handlers, mappers)
core/ports/spi/   portas de saída    (repositories, listeners, publishers, queries)
infrastructure/db/            entidades JPA, adapters de repositório, mappers de persistência
infrastructure/messaging/     listeners e publishers RabbitMQ
infrastructure/configuration/ configurações do Spring, properties e wiring da aplicação
application/                  Spring: controllers e application service
```

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
- `EventContractTest`, no `commons`, varre o pacote e prova que cada classe concreta de
  evento é construível. Foi escrito depois que essa falha passou por um `mvn verify` verde:
  ela não aparece no arranque nem nos testes de domínio, só no listener — serviços
  saudáveis, `POST /hotel/booking` respondendo 200 e a saga parada. Evento novo entra na
  cobertura sozinho; não há lista para manter.
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
- **Retry sem estado, com backoff exponencial**: `max-retries: 2` (três tentativas ao todo),
  1s → 2s, teto de 10s, em `spring.rabbitmq.listener.simple.retry.*`. O Boot 4 **não tem
  `max-attempts`** e ignora em silêncio chave que não conhece — o
  `DeadLetterConfigurationTest` de cada serviço lê o `application.yml` real e prova que o
  binding pegou. Cada tentativa roda numa transação própria (o interceptor envolve o
  listener por fora), e a thread do consumidor fica bloqueada durante o backoff: uma
  mensagem venenosa segura a fila por 2 a 4,5s.
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
  deduplicar é papel do consumidor (#7), e um dia que a deduplicação morar no `commons` esse
  teste deve ser invertido.
- **Desligamento gracioso (SIGTERM) não está configurado nem provado (#41).** Nenhum
  `application.yml` define o prazo de desligamento; vale o padrão do container (5 s), que o
  Boot 4.1 não expõe. Lido no código do Spring AMQP, sem teste: passado o prazo, o container
  interrompe a thread, e a interrupção na espera do retry vira "retry esgotado" — o
  recoverer manda a mensagem para a DLQ e o container a confirma, em vez de devolvê-la à fila.

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

## Skills do projeto

- **`registrar-ideia`** (`.claude/skills/registrar-ideia/SKILL.md`) — registra um achado do
  código de 2023 como ideia de post na base `Posts — Refatoração TCC` do Notion. Dispara
  quando o Gabriel diz que algo "daria um post" / "vale um post" / "é um achado", ou quando
  pede para listar e atualizar as ideias já registradas. Requer o MCP do Notion.
