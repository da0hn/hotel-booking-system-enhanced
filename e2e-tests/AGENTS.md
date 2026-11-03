# AGENTS.md — e2e-tests

Instruções para quem mexe nos testes ponta a ponta. Elas complementam o `AGENTS.md` da raiz,
onde ficam o comando do profile `e2e` (tabela de *Comandos*) e as seções de arquitetura citadas
abaixo (*Actuator* e *Erros HTTP*).

O módulo `e2e-tests` reúne os testes que sobem o sistema inteiro: PostgreSQL e RabbitMQ pelo
Testcontainers e `hotel`, `booking`, `customer` e `payment` **como processos filhos**. O
`E2eEnvironment` faz essa subida e é a base de qualquer teste novo do módulo; os testes são
`Specification`s do Spock em `src/test/groovy/com/hotel/booking/system/e2e/`.

Hoje há três. O `SagaIT` é o único teste que prova que as quatro pontas se entendem. Ele
dispara `POST /hotel/booking` no `hotel-service` e lê o desfecho no `customer-service` e nos
bancos. Os quatro cenários são pagamento aprovado (`RESERVED`), último quarto já ocupado
(`RESERVATION_FAILED`, sem gravar a reserva), `booking-service` derrubado com `kill` no meio
da transação (a saga retoma ao reiniciar, com uma única reserva) e pagamento recusado
(`PAYMENT_FAILED`, reserva `CANCELED`). É a rede de quem mexe no contrato de eventos, na
fronteira transacional ou na regra de disponibilidade: um evento movido de pacote, um pacote
fora do `TrustedEventPackages` ou um listener sem `@Transactional` deixam os quatro serviços
saudáveis e a saga parada, e nenhum teste de unidade enxerga isso.

Os outros dois não tocam na saga. O `ActuatorExposureIT` bate nos quatro serviços e confere o
que o Actuator mostra por fora (ver *Actuator*, na seção de arquitetura). O
`HttpErrorContractIT` confere o contrato de erro HTTP do `hotel-service` e do
`customer-service` nos jars de verdade: o status e a chave de `message` de cada situação (ver
*Erros HTTP*). Por serem `Specification`s à parte, cada um paga um arranque do ambiente inteiro.

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
  gancho para ser provocada daqui: ela é coberta no `DeadLetterRetryIT` (a garantia crua do broker) e no `BookingIdempotencyIT` (a
  deduplicação, #7).
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
