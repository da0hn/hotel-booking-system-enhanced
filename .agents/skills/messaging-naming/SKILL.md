---
name: messaging-naming
description: Padroniza a nomenclatura de mensageria do projeto. Use sempre que a tarefa criar, renomear, documentar ou revisar exchanges, filas, routing keys, bindings, topics, subjects, eventos, mensagens, CloudEvents ou recursos equivalentes de um broker, mesmo quando o usuário não mencionar explicitamente esta skill.
compatibility: Requer apenas acesso aos arquivos do repositório; consulte as documentações oficiais vinculadas quando precisar validar uma regra específica do transporte.
---

# Nomenclatura de mensageria

Use nomes técnicos estáveis, hierárquicos e orientados ao domínio. Separe sempre três
responsabilidades:

- o `exchange` ou `topic` organiza a publicação e o roteamento;
- a `routing key`, o `subject` ou o `topic name` identifica o fluxo semântico do evento;
- a `queue` ou assinatura identifica o consumidor que mantém aquela leitura.

Não trate o nome da fila como sinônimo do nome do evento. O mesmo evento pode ser
consumido por vários serviços, cada um com sua própria fila.

## Convenção do projeto

Use `.` como separador, letras minúsculas, dígitos e palavras curtas em ASCII. Não use
espaços, acentos ou nomes dependentes de uma classe Java, hostname, partição ou detalhe
interno do consumidor.

### Exchange

Estruture como:

```text
<sistema>.<dominio>.events
```

Exemplo:

```text
hotel.booking.events
```

O exchange representa o espaço de publicação de um domínio e pode transportar vários
eventos relacionados. Não inclua o evento nem o consumidor no nome do exchange. Evite
prefixos redundantes como `exchange.` quando o campo de configuração já informa que o
recurso é um exchange.

### Routing key, topic ou subject

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

### Queue ou assinatura

Estruture como:

```text
<consumidor>.<dominio>.<agregado>.<evento>.v<major>
```

Exemplo:

```text
booking-service.booking.room.requested.v1
```

Inclua o consumidor porque a fila representa a assinatura persistente daquele serviço.
Outro consumidor deve possuir outra fila, mesmo que leia a mesma routing key:

```text
customer-service.booking.room.requested.v1
```

Não coloque o consumidor no exchange nem na routing key. Evite também incluir detalhes
como número de partições, hostname, pod, grupo temporário ou versão de deploy.

### Dead-letter e filas auxiliares

Use sufixos explícitos derivados da fila principal:

```text
booking-service.booking.room.requested.v1.dlq
booking-service.booking.room.requested.v1.retry
```

Não crie uma nomenclatura paralela para filas auxiliares. Preserve o nome da assinatura
principal e acrescente apenas o papel operacional.

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

## Regras para alterações existentes

Antes de renomear um recurso, localize todos os publishers, consumers, bindings,
configurações, testes, dashboards e políticas que usam o nome. Em RabbitMQ, declare o
novo exchange ou fila e o novo binding antes de remover o antigo. Atualize produtor e
consumidor de forma coordenada e confirme como as mensagens já publicadas serão drenadas.

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

Essas referências definem semântica, restrições e recomendações. A estrutura exata acima
é a convenção interna do projeto e deve ser aplicada de maneira consistente.
