---
name: messaging-conventions
description: Padroniza a nomenclatura de mensageria para qualquer sistema de filas ou pub/sub. Use sempre que a tarefa criar, renomear, documentar ou revisar exchanges, filas, routing keys, bindings, topics, subjects, streams, subscriptions, consumer groups, eventos, mensagens, CloudEvents ou recursos equivalentes de qualquer broker, mesmo quando o usuário não mencionar explicitamente esta skill.
compatibility: Requer apenas acesso aos arquivos do repositório; consulte as documentações oficiais vinculadas quando precisar validar uma regra específica do transporte.
---

# Convenções de mensageria

Use esta skill em qualquer tecnologia de filas, publicação/assinatura ou streaming.
Primeiro identifique os recursos que realmente existem no sistema escolhido; depois
aplique a convenção base e as restrições específicas do transporte. Não force os nomes
`exchange`, `queue` ou `routing key` em plataformas que não possuem esses conceitos.

Use nomes técnicos estáveis, hierárquicos e orientados ao domínio. Separe sempre três
responsabilidades, adaptando os nomes aos conceitos equivalentes da plataforma:

- o `exchange` ou `topic` organiza a publicação e o roteamento;
- a `routing key`, o `subject` ou o `topic name` identifica o fluxo semântico do evento;
- a `queue`, `subscription`, `consumer group` ou consumidor durável identifica a leitura
  mantida por um consumidor.

Não trate o nome da fila, assinatura ou grupo de consumidores como sinônimo do nome do
evento. O mesmo evento pode ser consumido por vários serviços, cada um com sua própria
leitura independente.

## Convenção base

Use `.` como separador, letras minúsculas, dígitos e palavras curtas em ASCII. Não use
espaços, acentos ou nomes dependentes de uma classe Java, hostname, partição ou detalhe
interno do consumidor.

Quando a plataforma exigir outro separador ou possuir restrições próprias, preserve a
mesma sequência semântica e adapte somente a representação. Por exemplo, use `/` para
níveis de tópicos MQTT e `.` para subjects NATS ou routing keys de exchanges `topic`.

## Mapeamento por plataforma

Antes de nomear um recurso, classifique sua função:

| Função | RabbitMQ/AMQP | Kafka | NATS | MQTT |
| --- | --- | --- | --- | --- |
| Espaço de publicação/roteamento | `exchange` | `topic` | não há exchange separado; use o `subject` | não há exchange separado; use o `topic name` |
| Endereço semântico do fluxo | `routing key` | `topic`, chave ou `type` no evento | `subject` | `topic name` |
| Leitura persistente do consumidor | `queue` | `consumer group` | `queue group` ou consumidor durável | `subscription`/sessão |
| Regra de entrega | `binding` | assinatura do grupo | subscription/filter | `topic filter` |

Essa tabela é um mapa conceitual, não uma equivalência operacional perfeita. Uma
plataforma pode combinar publicação, retenção e assinatura em um único recurso. Nesses
casos, nomeie o recurso pelo papel que ele desempenha no desenho e registre as diferenças
de retenção, distribuição e reprocessamento separadamente.

### Espaço de publicação

Estruture como:

```text
<sistema>.<dominio>.events
```

Exemplo:

```text
hotel.booking.events
```

O exchange, topic ou subject raiz representa o espaço de publicação de um domínio e pode
transportar vários eventos relacionados. Não inclua o evento nem o consumidor nesse
espaço quando a plataforma permitir separá-los. Evite prefixos redundantes como
`exchange.` ou `topic.` quando o campo de configuração já informa o tipo do recurso.

Quando a plataforma não possuir um espaço separado de publicação, aplique a estrutura
diretamente ao endereço semântico do fluxo e não crie um nome artificial para um
exchange inexistente.

### Endereço do fluxo

Estruture como:

```text
<dominio>.<agregado>.<evento>.v<major>
```

Exemplo:

```text
booking.room.requested.v1
```

Use um nome que comunique a intenção ou ocorrência de negócio. Prefira eventos em forma
estável e semanticamente clara, como `requested`, `confirmed` e `canceled`. Em RabbitMQ
com exchange do tipo `topic`, preserve os segmentos separados por `.` para permitir
assinaturas como:

```text
booking.room.*.v1
booking.room.#
```

Não use prefixos redundantes como `routing-key.`. O contexto de configuração já informa
que o valor é uma routing key.

Para Kafka, trate o mesmo valor como o nome semântico do `topic` apenas se o desenho
escolher um tópico por evento. Se o tópico agrupar vários eventos, mantenha o nome do
tópico no nível do fluxo e use `type` ou headers para distinguir os eventos.

Para NATS, use a mesma hierarquia como `subject`; publique sempre o subject completo e
reserve wildcards para assinaturas.

### Fila, assinatura ou grupo de consumidores

Estruture como:

```text
<consumidor>.<dominio>.<agregado>.<evento>.v<major>
```

Exemplo:

```text
booking-service.booking.room.requested.v1
```

Inclua o consumidor porque a fila, assinatura ou grupo representa a leitura mantida por
aquele serviço. Outro consumidor deve possuir outra leitura independente, mesmo que leia
o mesmo fluxo:

```text
customer-service.booking.room.requested.v1
```

Não coloque o consumidor no espaço de publicação nem no endereço semântico do fluxo.
Evite também incluir detalhes como número de partições, hostname, pod, grupo temporário
ou versão de deploy.

### Dead-letter e filas auxiliares

Use sufixos explícitos derivados da fila principal:

```text
booking-service.booking.room.requested.v1.dlq
booking-service.booking.room.requested.v1.retry
booking-service.booking.room.requested.v1.scheduled
```

Não crie uma nomenclatura paralela para filas auxiliares. Preserve o nome da assinatura
principal e acrescente apenas o papel operacional. Para retries agendados, prefira um
nome estável como `.retry` ou `.scheduled` e registre `attempt`, `delay` e o horário de
execução como atributos da mensagem, span ou log; não crie uma fila por tentativa,
intervalo ou identificador de negócio sem uma necessidade operacional explícita.

### Tipo semântico do evento

Quando o evento usar CloudEvents, estruture `type` como:

```text
<reverse-dns>.<dominio>.<agregado>.<evento>.v<major>
```

Exemplo:

```text
com.hotel.booking.system.booking.room.requested.v1
```

Use uma autoridade reverse-DNS pertencente à organização quando houver uma disponível.
O valor de `type` é o identificador semântico do evento; ele não é o nome do exchange,
da fila ou da routing key. Pode espelhar a parte semântica da routing key, mas essa
correspondência é uma convenção do projeto, não uma exigência do CloudEvents.

### Origem do evento

Preencha `source` com um identificador estável do contexto que produziu o evento. Ele é
um `URI-reference` e um URI absoluto é preferível. `source` identifica o produtor ou o
contexto de origem da ocorrência; não identifica o exchange, topic, fila, routing key,
hostname, pod, thread ou versão do deploy. O valor deve permanecer estável enquanto o
mesmo produtor lógico continuar responsável pelo evento.

Quando a organização possuir um domínio próprio, prefira um URI HTTPS sob esse domínio:

```text
https://events.<dominio-organizacao>/sources/<sistema>/<servico>
```

Exemplo:

```text
https://events.hotel-booking.com/sources/hotel-booking-system/hotel-service
```

Quando não houver um domínio apropriado, use este formato URN:

```text
urn:<sistema>:service:<servico>
```

Exemplo:

```text
urn:hotel-booking-system:service:hotel
```

Se o mesmo produtor lógico puder gerar o mesmo `id` em ambientes isolados que trocam
eventos entre si, inclua o ambiente como parte da origem:

```text
urn:<sistema>:environment:<ambiente>:service:<servico>
```

Use nomes minúsculos, estáveis e sem identificadores de instância. Em uma cadeia de
eventos, o `source` muda para o serviço que produziu cada novo evento, enquanto
`correlationid` permanece associado ao fluxo e `causationid` aponta para o evento
imediatamente anterior.

O par `source` + `id` deve ser único para cada evento distinto. Uma retransmissão do
mesmo evento pode conservar o mesmo par; um novo evento derivado deve receber um novo
`id`.

## Metadados do envelope

Separe os metadados do CloudEvents do conteúdo de negócio em `data`. Os atributos de
contexto usam nomes minúsculos em ASCII; os opcionais devem ser omitidos quando não se
aplicarem, em vez de receberem valores vazios, inventados ou `null`, salvo quando o
schema do projeto exigir explicitamente `null`.

| Campo | O que representa | Quando informar |
| --- | --- | --- |
| `specversion` | Versão da especificação CloudEvents usada para interpretar o envelope. | Sempre; use `1.0` para CloudEvents 1.x. |
| `id` | Identificador da ocorrência/evento produzido. | Sempre; gere um valor único dentro de `source`. |
| `source` | Contexto ou produtor lógico onde a ocorrência aconteceu. | Sempre; siga a convenção de origem desta skill. |
| `type` | Tipo semântico da ocorrência, incluindo a versão major do contrato quando aplicável. | Sempre; use o identificador reverse-DNS definido para o evento. |
| `time` | Momento em que a ocorrência aconteceu, em RFC 3339. | Informe quando conhecido; não use para representar o próximo horário de entrega. |
| `subject` | Entidade específica afetada pela ocorrência dentro de `source`. | Informe quando houver um alvo identificável ou quando filtros genéricos precisarem dele. |
| `datacontenttype` | Media type do conteúdo de `data`, como `application/json`. | Informe quando o formato não for implícito ou quando o evento atravessar protocolos. |
| `dataschema` | URI do schema ao qual `data` obedece. | Informe quando o schema for publicado; altere a URI em mudanças incompatíveis. |
| `data` | Dados específicos do domínio que descrevem a ocorrência. | O CloudEvents permite omiti-lo, mas eventos de negócio desta skill devem informá-lo. |
| `traceparent` | Contexto de trace distribuído do W3C propagado entre produtores e consumidores. | Informe ao usar a extensão de tracing ou quando houver contexto de trace ativo. |
| `tracestate` | Estado adicional específico dos vendors associado ao `traceparent`. | Informe somente quando recebido ou gerado pelo propagator e houver estado a preservar. |
| `correlationid` | Identificador estável do fluxo de negócio que relaciona vários eventos. | Informe em sagas, workflows e transações distribuídas; mantenha-o igual em todo o fluxo. |
| `causationid` | `id` do evento, comando ou mensagem que causou diretamente o evento atual. | Informe em eventos derivados de outra mensagem; omita no primeiro evento sem causa mensageada. |
| `causationsource` | `source` correspondente ao `causationid`, evitando ambiguidade entre produtores. | Informe junto de `causationid` quando mais de um produtor puder gerar ids semelhantes. |
| `retryattempt` | Contador da tentativa de processamento do evento, começando em `0`. | Informe quando retries precisarem ser portáveis ou auditáveis; incremente ao republicar, não em simples redelivery. |
| `scheduledat` | Próximo instante planejado para disponibilizar ou processar a mensagem, em RFC 3339 UTC. | Informe em mensagens agendadas, timeouts ou retries com backoff; omita em entregas imediatas. |

`traceparent` e `tracestate` pertencem à extensão de tracing do CloudEvents. Os campos
`correlationid`, `causationid`, `causationsource`, `retryattempt` e `scheduledat` são
extensões do projeto e devem ter seus tipos e semântica mantidos em um catálogo de
extensões. Nenhum deles é atributo obrigatório do núcleo do CloudEvents.

O `time` continua representando a ocorrência original mesmo quando a mensagem é
agendada ou republicada. Use `scheduledat` para o próximo processamento e
`retryattempt` para a tentativa operacional. Se esses dados forem específicos de um
broker e não precisarem sobreviver à troca de transporte, eles podem ficar em headers ou
metadados do broker em vez do envelope CloudEvents.

## Versionamento

Inclua somente a versão major do contrato no fluxo quando ela for necessária para
identificar uma versão incompatível:

```text
booking.room.requested.v1
booking.room.requested.v2
```

Mantenha `v1` quando a evolução for compatível. Crie `v2` quando mudar a interpretação
do evento ou quebrar consumidores existentes. Durante uma migração, mantenha a topologia
antiga e a nova em paralelo tempo suficiente para drenar mensagens e atualizar os
consumidores; não apenas troque a string e deixe o binding antigo desaparecer.

No CloudEvents, uma mudança incompatível deve normalmente alterar `type` e também a URI
de `dataschema`, caso esse atributo seja usado. O esquema de versionamento é uma decisão
do produtor; o sufixo `.v1` é a convenção adotada por este projeto.

## Exemplo aplicado ao projeto

Para `BookingRoomRequestedEvent`, use:

```text
exchange:    hotel.booking.events
routing-key: booking.room.requested.v1
queue:       booking-service.booking.room.requested.v1
dlq:         booking-service.booking.room.requested.v1.dlq
type:        com.hotel.booking.system.booking.room.requested.v1
```

Ao documentar o evento, mantenha o transporte e o contrato separados:

```json
{
  "specversion": "1.0",
  "id": "event-789",
  "source": "urn:hotel-booking-system:service:hotel",
  "type": "com.hotel.booking.system.booking.room.requested.v1",
  "data": {}
}
```

O envelope do CloudEvents não substitui o exchange, a fila ou a routing key. Ele
padroniza o contexto e o payload da mensagem.

## Observabilidade e tracing

A convenção facilita métricas, logs e traces porque produz destinos estáveis,
hierárquicos, únicos e de baixa cardinalidade. Ela permite agrupar operações por
sistema, domínio, agregado, evento e consumidor sem incluir dados que variam por
mensagem. Ela não implementa tracing sozinha: a instrumentação ainda precisa criar
spans e propagar o contexto entre produtor e consumidor.

Não inclua `traceId`, `spanId`, `reservationId`, `tenantId`, username, timestamp,
hostname, pod, partição ou número da tentativa em nomes de exchanges, filas, topics,
subjects, routing keys ou grupos. Esses valores pertencem a atributos de spans, logs,
headers ou ao `data` do evento. Quando um destino for inevitavelmente dinâmico, registre
o nome efetivo em `messaging.destination.name` e o padrão de construção em
`messaging.destination.template`.

Ao instrumentar uma publicação ou consumo, mapeie os conceitos para as convenções do
OpenTelemetry. Use o nome real da entidade operada pelo span:

| Conceito da convenção | Atributo ou operação OpenTelemetry | Exemplo |
| --- | --- | --- |
| Exchange ou topic do produtor | `messaging.destination.name` | `hotel.booking.events` |
| Fila, subscription ou grupo do consumidor | `messaging.destination.name` | `booking-service.booking.room.requested.v1` |
| Sistema de mensageria | `messaging.system` | `rabbitmq`, `kafka`, `nats` ou `mqtt` |
| Publicação | `messaging.operation.type` | `send` |
| Recebimento/processamento | `messaging.operation.type` | `receive` ou `process` |
| Identificador da mensagem | `messaging.message.id` | valor de `CloudEvents.id` |
| Evento CloudEvents | `cloudevents.event_type` | `com.hotel.booking.system.booking.room.requested.v1` |
| Origem CloudEvents | `cloudevents.event_source` | `urn:hotel-booking-system:service:hotel` |
| Assunto CloudEvents | `cloudevents.event_subject` | `reservation-order/abc` |

Use `messaging.operation.name` apenas quando a instrumentação ou o projeto precisar de
um nome legível para a operação. Mantenha-o estável, por exemplo `send
hotel.booking.events` ou `process booking-service.booking.room.requested.v1`; não o
derive de ids, tentativas ou outros valores por mensagem. Preencha os atributos usados
para amostragem no momento da criação do span.

Para manter um trace distribuído:

1. Injete o contexto com um propagator OpenTelemetry nos headers ou carrier suportados
   pelo transporte ao publicar.
2. Propague `traceparent` e, quando existir, `tracestate`; extraia-os no consumidor
   antes de criar o span de processamento.
3. Preserve os headers específicos do broker e do protocolo. Se usar a extensão de
   tracing do CloudEvents, carregue `traceparent` e `tracestate` no envelope conforme a
   extensão, mas não trate essa extensão como substituta dos headers de tracing do
   transporte.
4. Use `CloudEvents.id` como identidade da mensagem para relacionar publicação,
   entrega, retry e processamento. Use ids de negócio como atributos de correlação,
   nunca como parte do nome do destino.

Para retries, DLQs e filas agendadas, preserve o mesmo nome semântico do evento e
adicione o papel operacional no destino. Diferencie a tentativa e o atraso em atributos
como `messaging.operation.type`, `messaging.message.id`, `messaging.destination.name`,
`attempt` e `delay`, conforme as convenções e capacidades da instrumentação. Assim, um
dashboard consegue comparar o fluxo normal, retry e DLQ por sufixo sem criar uma série
temporal nova para cada tentativa ou mensagem.

### Exemplo observável

Para o evento `BookingRoomRequestedEvent`:

```text
exchange/topic: hotel.booking.events
routing-key/subject: booking.room.requested.v1
queue/subscription: booking-service.booking.room.requested.v1
retry: booking-service.booking.room.requested.v1.retry
dlq: booking-service.booking.room.requested.v1.dlq
```

O span de publicação pode registrar:

```text
messaging.system = rabbitmq
messaging.destination.name = hotel.booking.events
messaging.operation.type = send
messaging.message.id = event-789
cloudevents.event_type = com.hotel.booking.system.booking.room.requested.v1
```

O span de processamento registra o destino da leitura:

```text
messaging.system = rabbitmq
messaging.destination.name = booking-service.booking.room.requested.v1
messaging.operation.type = process
messaging.message.id = event-789
```

O `traceparent` conecta os spans, enquanto `event-789`, `reservationOrderId`,
`attempt` e `delay` permitem correlação e diagnóstico sem aumentar a cardinalidade dos
nomes de infraestrutura.

## Regras para alterações existentes

Antes de renomear um recurso, localize todos os publishers, consumers, bindings,
configurações, testes, dashboards e políticas que usam o nome. Identifique também
mensagens retidas, assinaturas, grupos de consumidores e políticas de segurança que
dependem do endereço. Crie o recurso novo e a regra de entrega antes de remover o antigo,
quando a plataforma permitir. Atualize produtores e consumidores de forma coordenada e
confirme como as mensagens já publicadas serão drenadas ou migradas.

Em RabbitMQ, declare o novo exchange ou fila e o novo binding antes de remover os
antigos. Em Kafka, crie o novo topic e faça a migração dos registros, pois o topic não é
renomeado diretamente. Em NATS ou MQTT, atualize publishers e subscriptions de forma
coordenada, observando as garantias de retenção do sistema escolhido.

Não faça uma alteração puramente textual quando ela muda o endereço efetivo do recurso.
Considere-a uma migração de topologia e registre a compatibilidade entre os nomes antigo
e novo.

## Referências normativas e de projeto

- [RabbitMQ — Exchanges](https://www.rabbitmq.com/docs/exchanges): define exchange, binding, routing key, exchanges `direct`, `fanout` e `topic`.
- [AMQP 0-9-1 Specification](https://www.rabbitmq.com/resources/specs/amqp0-9-1.pdf): descreve routing key como endereço virtual usado para decidir o roteamento.
- [Apache Kafka — Multi-Tenancy](https://kafka.apache.org/42/operations/multi-tenancy/): recomenda nomes hierárquicos para topics e evitar consumidor ou detalhes técnicos no nome.
- [NATS — Subjects](https://docs.nats.io/nats-concepts/subjects): recomenda namespace no início, identificadores no final e intenção de negócio na hierarquia.
- [MQTT 5.0 — Topic Names and Topic Filters](https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html): define níveis hierárquicos, separador `/` e uso de wildcards apenas em filtros.
- [CloudEvents — Core Specification](https://github.com/cloudevents/spec/blob/ce%40stable/cloudevents/spec.md): define os atributos do evento e recomenda prefixo reverse-DNS para `type`.
- [CloudEvents Primer — Versioning](https://github.com/cloudevents/spec/blob/ce%40stable/cloudevents/primer.md): orienta a evolução de `type` e `dataschema`.
- [OpenTelemetry — Messaging spans](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/): define atributos de destinos, mensagens e operações para spans de mensageria.
- [OpenTelemetry — CloudEvents spans](https://opentelemetry.io/docs/specs/semconv/cloudevents/cloudevents-spans/): define atributos de CloudEvents para observabilidade.
- [OpenTelemetry — Context propagation](https://opentelemetry.io/docs/specs/otel/context/api-propagators/): descreve propagators para injetar e extrair contexto em mensagens.
- [CloudEvents — Distributed Tracing Extension](https://github.com/cloudevents/spec/blob/main/cloudevents/extensions/distributed-tracing.md): define o uso de `traceparent` e `tracestate` em CloudEvents.

Essas referências definem semântica, restrições e recomendações. A estrutura exata acima
é a convenção base desta skill; aplique-a ao projeto quando a plataforma permitir e
adapte apenas o que for necessário para respeitar o transporte escolhido.
