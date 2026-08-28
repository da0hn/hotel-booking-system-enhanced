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

O toolchain do host resolve `java` para o **JDK 25** (mise), mas o projeto **não compila
nele**: o Lombok gerenciado pelo Spring Boot 3.2.0 não suporta JDK 25 e o annotation
processor é desabilitado em silêncio — os erros aparecem como `constructor ... cannot be
applied to given types` e `cannot infer type arguments`, mascarando a causa real. Sempre
force o JDK 21:

```bash
export JAVA_HOME=$(cygpath -w "$HOME/AppData/Local/mise/installs/java/21.0.2")
```

| Objetivo | Comando |
|---|---|
| Build completo (reator) | `mvn -B clean install -DskipTests` |
| Rodar todos os testes | `mvn -B test` |
| Testes de um módulo | `mvn -B test -pl hotel-service` |
| Um teste específico | `mvn -B test -pl hotel-service -Dtest=RoomValidationTest` |
| Um método de teste | `mvn -B test -pl hotel-service -Dtest=RoomValidationTest#nomeDoMetodo` |
| Compilar só o `commons` e dependentes | `mvn -B compile -pl commons -am` |

Não há linter, formatter nem gate de cobertura configurado.

**Subir o ambiente** (a partir de `docker/`, com os volumes externos já criados — ver
README): `docker-compose -p hotel-booking-system -f common.yml -f services.yml up -d`.
Cada `*.dockerfile` roda `mvn clean package` do reator inteiro dentro do build, então
subir os 4 serviços recompila o projeto 4 vezes sem cache de dependências.

Para rodar um serviço isolado na IDE, as portas de banco default do `application.yml`
apontam para as portas publicadas pelo compose (3311/3312/3313), não para 3306.

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

Cada serviço tem banco MySQL próprio com migrations Flyway em `src/main/resources/db/migration`.

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
- A desserialização usa `Jackson2JsonMessageConverter`, que grava o **FQCN da classe no
  header `__TypeId__`**. Isso significa que **mover ou renomear uma classe de evento no
  `commons` quebra a comunicação entre serviços em versões diferentes** — mensagens já na
  fila deixam de ser desserializáveis.
- Exchanges, routing keys e filas são declarados em cada serviço via
  `RabbitMQConfiguration` + `@ConfigurationProperties` (`ExchangeProperties`,
  `RoutingKeyProperties`, `QueueProperties`), lidos de `app.rabbitmq.*` no `application.yml`.
