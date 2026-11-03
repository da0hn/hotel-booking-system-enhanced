# AGENTS.md — .github

Instruções para quem mexe nos workflows e no processo de release. Complementam o `AGENTS.md`
da raiz, onde ficam o fluxo de branches e PRs (*Processo de desenvolvimento*) e a regra de que
o agente não commita direto em `develop`, `master`, `release/*` nem `hotfix/*`.

## Publicação das imagens

O que cada gatilho publica no GHCR está em `.github/workflows/build.yml`.

A tag imutável (`-<run_number>`) é a que se cita num diagnóstico: ela responde qual
imagem está rodando no homelab. A móvel (`-latest`) existe só para quem não quer
descobrir o número do build, e nunca substitui a primeira.

`.github/workflows/back-merge.yml` abre o PR `master → develop` a cada push na `master`,
para que a correção que entrou por `hotfix/*` não desapareça no próximo corte de release.
Ele não faz o merge — só abre o PR, e reaproveita o que já estiver aberto.

O desenho completo do fluxo está em `docs/diagrams/06-gitflow-pipeline.jpg`.

## Gitflow

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
