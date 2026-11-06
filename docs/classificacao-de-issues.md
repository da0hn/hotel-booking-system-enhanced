# Classificação de issues

Como as issues deste repositório são classificadas. A skill `create-issue` lê as labels
com `gh label list`; este documento diz **quando** usar cada uma.

Toda issue aberta leva **um tipo**, **uma ou duas áreas**, **uma prioridade** e os
**serviços** que o código toca. O agrupamento por entrega é um milestone.

## Tipo (exatamente um)

| Label | Quando |
|---|---|
| `bug` | O comportamento observável contradiz um contrato ou regra já escritos: `AGENTS.md`, `README`, critério de aceite de outra issue. |
| `enhancement` | Capacidade nova para quem consome a API. |
| `task` | Trabalho técnico sem mudança de contrato visível: endurecimento, refatoração, infraestrutura, teste, CI. |

Na dúvida entre `task` e `enhancement`, pergunte se um cliente da API perceberia a
diferença. Se sim, é `enhancement`. Na dúvida entre `bug` e `task`, procure onde o
comportamento correto está escrito: se não está em lugar nenhum, não é `bug`.

## Área (uma, ou duas)

| Label | Cobre |
|---|---|
| `area: messaging` | RabbitMQ, eventos, saga |
| `area: persistence` | Banco de dados, JPA, migrations |
| `area: api` | Controllers, DTOs, contrato HTTP |
| `area: build` | Maven, Docker, toolchain e pipeline (workflows) |
| `area: tests` | Cobertura e testes automatizados |

`area: tests` é para a issue cujo **objetivo** é o teste. Toda feature já nasce com
testes (TDD, em *Processo de desenvolvimento*); marcá-la por isso esvaziaria a label.
Mais de duas áreas só no épico, que reúne as das filhas.

## Prioridade (obrigatória em issue aberta)

| Label | Quando |
|---|---|
| `priority: high` | Perde ou corrompe dado, quebra a saga, ou bloqueia outra issue. |
| `priority: medium` | Defeito ou limitação conhecida, sem perda de dado. |
| `priority: low` | Melhoria oportunista, ou endurecimento sem incidente. |

`medium` não é o valor de "ainda não decidi": a issue precisa caber na linha acima. Se
não couber em nenhuma, ela ainda não foi triada.

## Gatilho `e2e`

Não classifica a issue: liga o `.github/workflows/e2e.yml`. O workflow roda quando o PR tem
a label ou quando referencia (`GH-N` no branch, no título ou no corpo, ou `Closes #N`) uma
issue que a tem. **O workflow lê o nome da label**: renomeá-la ou apagá-la desliga o
gatilho sem erro nenhum.

Marque a issue que mexe no contrato de eventos, na fronteira transacional ou na regra de
disponibilidade, o mesmo critério que a seção *Testes ponta a ponta* do `AGENTS.md` dá
para rodar o e2e antes do PR.

## Serviço (um ou mais)

| Label | Módulo |
|---|---|
| `service: hotel-service` | `hotel-service` |
| `service: booking-service` | `booking-service` |
| `service: customer-service` | `customer-service` |
| `service: payment-service` | `payment-service` |
| `service: commons` | `commons` |

A label é o que se filtra (`gh issue list --label "service: booking-service"`). O prefixo
`[módulo]` do título continua para quem lê a lista: módulos separados por `/`
(`[booking]`, `[hotel/booking]`), mais `todos`, `build` e `ci` para o que não é um módulo.
A ordem dentro do prefixo não é regra.

Issue que afeta os quatro serviços (`[todos]`) não leva label de serviço: marcar os quatro
a faria aparecer em todo filtro e esconderia o que é específico de cada um. Issue só de
`build` ou `ci` também não leva.

## Épico, bloqueio e relações

- **Épico = milestone.** O nome é o do épico, sem número de versão e sem data. A descrição
  tem uma linha com o objetivo e o link da issue de design. O GitHub não o fecha sozinho: encerre-o
  à mão quando todas as issues dele estiverem fechadas. Uma issue pertence a no máximo um milestone, o que combina com épico e é o motivo
  de não usá-lo como tema.
- **Milestone não é release.** As versões `X.Y.Z` são branches e tags geradas pela
  pipeline (`.github/AGENTS.md`).
- **A issue de design do épico** (guarda-chuva) continua como pai das filhas, por
  sub-issues nativas, e entra no milestone como as filhas. É a última a fechar. Sub-issue
  e milestone cobrem os mesmos itens: ao criar uma filha nova, atribua o milestone também.
- **Bloqueio** se registra como dependência nativa do GitHub, e o texto "Bloqueada por #N"
  no corpo só explica o porquê. Texto sozinho envelhece: a #41 diz "Bloqueada por #7" e a #7
  já está fechada.
- Um agrupamento sem fases nem entrega única (os seguimentos da revisão de um PR, por
  exemplo) não é épico e usa só os eixos acima.

## Não criar

`epic`, `blocked` e labels de status. O milestone, a dependência nativa e o estado
aberto/fechado da issue já dizem isso, e uma segunda fonte diverge da primeira.
