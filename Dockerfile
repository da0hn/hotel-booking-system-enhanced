# Um Dockerfile para os quatro executáveis. O módulo entra por argumento, e
# não por copia-e-cola: antes havia quatro arquivos em `docker/dockerfile/`
# com o mesmo conteúdo e uma linha diferente cada, e eles divergiriam em
# silêncio na primeira vez que alguém editasse um só.
#
# Cada um daqueles arquivos rodava `mvn clean package` do reator INTEIRO —
# quatro imagens, quatro compilações completas dos cinco módulos, com testes.
# Aqui as camadas estão ordenadas por volatilidade: as dependências e o
# `commons` são idênticos nas quatro imagens e sobrevivem a qualquer mudança
# no código de um serviço.
#
# JDK 25. O que antes prendia o build no 21 não era o Lombok em si: era o
# javac, que a partir do 23 deixou de descobrir annotation processors pelo
# classpath e passou a assumir `-proc:none` quando ninguém declara o caminho.
# O `annotationProcessorPaths` no pom raiz declara esse caminho, e com ele o
# Lombok volta a rodar em qualquer JDK recente.
ARG JAVA_VERSION=25

FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS build
WORKDIR /build

# As dependências entram numa camada própria: elas mudam muito menos que o
# código, e o cache só é reaproveitado se o estável vier antes do volátil.
# Os cinco `pom.xml` entram porque o Maven precisa do reactor completo para
# resolver o parent e o agregador.
COPY pom.xml .
# O `lombok.config` entra junto dos poms, e não com o código: ele decide quais
# anotações o Lombok gera, então precisa estar no lugar antes da primeira
# compilação. Sem ele, o `@Jacksonized` das classes de evento sai apontando
# para o Jackson 2 e o build quebra em `package
# com.fasterxml.jackson.databind.annotation does not exist`.
COPY lombok.config .
COPY commons/pom.xml commons/
COPY hotel-service/pom.xml hotel-service/
COPY booking-service/pom.xml booking-service/
COPY payment-service/pom.xml payment-service/
COPY customer-service/pom.xml customer-service/
RUN mvn -B -q dependency:go-offline -DexcludeArtifactIds=commons

# O `commons` vem antes e sozinho. Nenhum serviço depende de outro serviço,
# então esta camada é idêntica nas quatro imagens e só é invalidada quando o
# contrato de eventos muda de verdade.
COPY commons/src commons/src

# O código do módulo pedido é a primeira camada que difere entre as quatro
# imagens, e é de propósito que ela seja a última.
ARG MODULE
RUN test -n "${MODULE}" || (echo 'MODULE é obrigatório' && exit 1)
COPY ${MODULE}/src ${MODULE}/src

# `-am` constrói o `commons` junto, no mesmo reactor. É deliberado não usar
# `install` do `commons` numa camada separada: a versão do projeto é
# `${revision}`, e sem o `flatten-maven-plugin` o pom que iria para o `~/.m2`
# carregaria o placeholder não resolvido, quebrando a resolução do serviço.
# Os testes ficam de fora porque o job `provas` do workflow já rodou o
# reactor inteiro — repeti-los aqui provaria a mesma coisa quatro vezes.
RUN mvn -B -q -pl ${MODULE} -am -DskipTests package

FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime
ARG MODULE

# O Alpine não traz o banco de fusos. Sem `tzdata`, o `TZ` abaixo é ignorado
# e a JVM cai para UTC — o que deslocaria em três horas as datas de check-in
# e check-out que o domínio valida.
RUN apk add --no-cache tzdata
ENV TZ=America/Cuiaba

# O processo não roda como root: um serviço exposto na rede do homelab com
# privilégio no container é superfície desnecessária.
RUN addgroup -S app && adduser -S -G app app
USER app

WORKDIR /service
COPY --from=build /build/${MODULE}/target/${MODULE}-*.jar /service/api.jar

# `sh -c` existe para expandir `JAVA_OPTS` em tempo de execução; a forma
# exec de ENTRYPOINT não expandiria a variável. As flags de locale vêm dos
# dockerfiles anteriores e são preservadas porque o domínio formata valores
# monetários e datas conforme a região.
ENTRYPOINT [ "sh", "-c", "java -Djava.security.egd=file:/dev/./urandom -Duser.region=BR -Duser.language=pt ${JAVA_OPTS} -jar /service/api.jar" ]
