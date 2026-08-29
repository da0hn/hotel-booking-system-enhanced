---
name: registrar-ideia
description: Registra uma ideia de post na base "Posts — Refatoração TCC" do Notion, a partir de um achado no código durante o mapeamento ou a refatoração do hotel-booking-system-enhanced. Use sempre que o Gabriel disser que algo "daria um post", "vale um post", "isso é um achado", "registra essa ideia", "isso merece virar conteúdo", ou quando ele descrever uma decisão de 2023 que se revelou errada e comentar que quer escrever sobre ela. Use também quando ele pedir para listar, revisar ou atualizar as ideias já registradas. Não use para criar issues no repo, escrever o texto do post, nem para registrar achado que ele não marcou como lição.
---

# Registrar ideia de post

Esta skill captura o achado no único momento em que ele existe por inteiro: durante a sessão, com o código de 2023 aberto na frente.

Depois que o problema é consertado, a informação mais valiosa da série já sumiu — **por que a decisão errada parecia certa na época**. O diff do merge mostra o que mudou, mas não recupera isso. Por isso o registro acontece aqui, e não depois.

## Contexto

O `hotel-booking-system-enhanced` é a refatoração do TCC de 2023 do Gabriel: reserva de hotel em quatro microsserviços Java/Spring Boot comunicando por eventos no RabbitMQ, com saga orquestrada pelo `hotel-service`. A arquitetura está descrita no `AGENTS.md` — leia de lá, não reconstrua aqui.

**A linha de base congelada é o commit `b27facb`.** Tudo depois dele (`994834d`, `d10d961`) é documentação e diagrama; nenhuma linha de código de 2023 foi tocada. Confirme com `git log --oneline b27facb..HEAD --stat` quando precisar afirmar que um achado é original da base.

A série de posts que sai desse trabalho tem uma premissa: **eu errei e vou te contar.** Todo registro precisa servir a ela. Um achado que só descreve um antipattern genérico não é material — o material é a distância entre o que parecia certo e o que era.

### Destino

Base `Posts — Refatoração TCC`, filha da página "Hotel Booking System Enhanced":

```
collection://2a8c7e30-86f2-49b8-8281-8ae7aa7a8c9d
```

Requer o MCP do Notion (`mcp__claude_ai_Notion__*`). Se ele não estiver disponível, **não improvise um arquivo local nem diga que registrou** — avise que o MCP está fora e ofereça guardar o material do Passo 2 na conversa até ele voltar.

## Quando NÃO registrar

Ser seletivo é o que mantém a série boa. A unidade editorial é a **lição**, não a issue. O repo tem 14 issues abertas; a base tem 6 páginas. Essa proporção é intencional.

Não registre quando:

- O achado é uma correção mecânica sem lição por trás (typo, import não usado, bump de dependência).
- A lição é genérica e não depende deste código — "use constantes em vez de números mágicos" não é post.
- Já existe uma linha na base cobrindo a mesma família de achado. Vários achados costumam colapsar num post só.

Se o achado não passar nesse filtro, diga isso em uma frase e siga com o trabalho. Não registre "por garantia".

## Passo 1 — Checar duplicata antes de qualquer coisa

Sessões diferentes produzem achados da mesma família. Sem essa checagem a base acumula três páginas sobre o mesmo assunto e a série perde o fio.

Rode a query — ela é a fonte da verdade, não a tabela abaixo:

```
mcp__claude_ai_Notion__notion-query-data-sources
  data_source_urls: ["collection://2a8c7e30-86f2-49b8-8281-8ae7aa7a8c9d"]
  query: SELECT "userDefined:ID", "Título", "Eixo", "Status", "Gancho", "Issue", url
         FROM "collection://2a8c7e30-86f2-49b8-8281-8ae7aa7a8c9d"
         ORDER BY "userDefined:ID"
```

Instantâneo de 2026-08-28, só para orientar o julgamento de "mesma família":

| ID | Eixo | Assunto coberto |
| --- | --- | --- |
| POST-1 | Arquitetura | Saga coreografada no diagrama, orquestrada no código |
| POST-2 | Arquitetura | Rollback que funciona por acaso (filtro `CANCELED`, sem compensação) |
| POST-3 | Mensageria | `new Queue(nome, true)` sem DLX/TTL/retry, poison message |
| POST-4 | Mensageria | FQCN no header `__TypeId__` acopla serviços a nomes de classe |
| POST-5 | Infraestrutura | Volumes Docker apontando para `D:/opt`, só roda na máquina de origem |
| POST-6 | Observabilidade | Saga distribuída sem correlação nem rastro |

Se encontrar candidata, **proponha atualizar a existente** em vez de criar nova, mostrando o que já está registrado e o que este achado acrescenta. Só crie página nova se o Gabriel confirmar que são lições distintas.

As seis páginas acima foram escritas antes desta skill e o corpo delas varia. Ao atualizar uma, deixe-a no formato do Passo 5 — não replique o formato antigo que encontrar.

## Passo 2 — Reunir o material

Antes de escrever, tenha em mãos, do próprio código:

- **O trecho concreto.** Arquivo, classe, método. Um achado sem âncora no código vira post vago.
- **Por que parecia certo em 2023.** Qual restrição, qual crença, qual tutorial da época levava a essa decisão. **É o item de que a série inteira depende e o único que não dá para deduzir do código** — se não souber, pergunte ao Gabriel.
- **O que revela o erro.** Qual cenário quebra, e por que os testes de caminho feliz não pegam.
- **Qual é a lição transferível.** O que alguém que nunca viu este repo leva embora.

Se algum desses quatro estiver vazio, o registro fica fraco. Pergunte antes de escrever, uma pergunta por vez.

## Passo 3 — O gancho vem do Gabriel

O gancho é a primeira linha do post e é o ativo mais importante da série.

Proponha uma opção e peça confirmação. Não registre um gancho que ele não viu.

Um bom gancho é uma ou duas frases, em primeira pessoa, que admitem algo específico. Compare:

- Fraco: "Erros comuns em sagas distribuídas."
- Bom: "Em 2023 eu defendi um TCC dizendo que minha saga era coreografada. Três anos depois, abri o código e descobri que ela era orquestrada."

- Fraco: "A importância de dead-letter queues."
- Bom: "Minhas filas eram duráveis e nada além disso. Uma exceção no listener bloqueava todas as mensagens seguintes."

## Passo 4 — Procurar a issue correspondente

O campo `Issue` amarra o post ao trabalho de refatoração. Procure antes de deixar vazio:

```bash
gh issue list --limit 50 --json number,title,labels,url
```

Para filtrar pelo eixo, use `--label` com a área correspondente:

| Eixo | Labels `area:` que costumam casar |
| --- | --- |
| Arquitetura | `area: messaging`, `area: persistence`, `area: api` |
| Mensageria | `area: messaging` |
| Observabilidade | `area: api` (hoje sem issue dedicada) |
| Infraestrutura | `area: build` |
| Testes | `area: tests` |
| Dados | `area: persistence` |

O mapa é guia, não bijeção — confirme lendo o título da issue.

**Mostre as candidatas e peça confirmação antes de preencher.** Sem confirmação explícita, deixe `Issue` vazio: um vínculo errado é pior que um campo em branco, porque a fase de escrita vai puxar o contexto da issue errada. Nunca invente número de issue.

## Passo 5 — Criar a página

Use `mcp__claude_ai_Notion__notion-create-pages` com
`parent: { type: "data_source_id", data_source_id: "2a8c7e30-86f2-49b8-8281-8ae7aa7a8c9d" }`.

Defina um `icon` — um emoji que represente o mecanismo do achado (🔁 para reentrega em laço, 🧭 para orquestração, 📦 para infraestrutura). O ícone entra em `icon`, **nunca no `Título`**.

Propriedades:

| Campo | Conteúdo |
| --- | --- |
| `Título` | A lição, não o sintoma. "Meu rollback funcionava por acaso", não "Falta compensação na saga". Sem emoji. |
| `Status` | `Ideia`, sempre. Os outros status pertencem às fases seguintes. |
| `Eixo` | Exatamente um de: `Arquitetura`, `Mensageria`, `Observabilidade`, `Infraestrutura`, `Testes`, `Dados`. |
| `Gancho` | A frase confirmada no Passo 3. |
| `Issue` | URL confirmada no Passo 4, ou vazio. |
| `Diagrama` | Um dos arquivos reais de `docs/diagrams/`, ou a descrição do que precisaria ser produzido. |

Diagramas que existem hoje — não invente nome de arquivo:

| Arquivo | Mostra |
| --- | --- |
| `01-containers.jpg` | Serviços, bancos e broker |
| `02-saga-events.jpg` | Fluxo de eventos da saga de reserva |
| `03-hexagonal.jpg` | Camadas dentro de um serviço |
| `04-queues.jpg` | Exchanges, routing keys e filas |
| `05-data-model-antes-mysql.jpg` | Modelo de dados de antes da migração: três bancos MySQL |
| `05-data-model-depois-postgres.jpg` | O mesmo modelo depois: três schemas numa instância PostgreSQL |

Se o achado precisar de imagem que ainda não existe, escreva a descrição em vez do nome (`"Antes/depois: waterfall no Tempo"`).

Corpo da página — formato curto, sem headings, exatamente nesta ordem:

```markdown
[Um a três parágrafos: onde está o achado (arquivo, classe, método), o que o código
faz de fato, e por que aquilo parecia certo em 2023. Este último ponto não vira
seção separada, mas precisa estar aqui — é a premissa da série.]

Ângulo do post: [como abrir e como fechar; uma ou duas linhas.]

Gancho para a continuação: [o que o próximo post puxa daqui, se houver.]
```

Deixe `Publicar em` e `Link do post` vazios — pertencem a fases posteriores.

## Passo 6 — Confirmar e voltar ao trabalho

Informe em uma linha o que foi registrado, o `POST-N` e o link da página. Não resuma o conteúdo de volta: ele acabou de ser escrito e o Gabriel estava presente.

Volte imediatamente à tarefa que estava em andamento. O registro é um desvio curto dentro da sessão de desenvolvimento, não o assunto da sessão.

## Modo leitura — listar, revisar, atualizar

Quando o pedido for sobre as ideias já registradas, não passe pelos passos de criação:

- **Listar / revisar**: a query do Passo 1 basta. Apresente como tabela, agrupada por `Eixo`.
- **Ler uma página inteira**: `notion-fetch` com a `url` que veio da query.
- **Atualizar**: `notion-update-page` com `command: "update_properties"` para campos, ou `"update_content"` para o corpo. Mostre o que vai mudar antes de aplicar.

## O que esta skill não faz

- **Não escreve o post.** O texto sai da tarefa diária do Cowork e passa pela revisão do Gabriel. Aqui só entra a ideia.
- **Não cria a issue no repo.** São artefatos separados, com critérios diferentes — para issue, use a skill `create-github-issue`.
- **Não move o `Status`.** Toda página nasce em `Ideia`; a promoção para `Matéria-prima` em diante é decisão de outra fase.
- **Não dispara sozinha.** Só roda quando o Gabriel decide que aquilo é uma lição. Registro automático a cada issue enche a base de não-lições e destrói o filtro que faz a série funcionar.
