# PricePulse-backend

Backend do PricePulse — repositório separado do app Android (`PricePulse`), conforme decisão de
topologia registrada em `receiptanalysis-slice-report.md` do repositório do app.

Este é só o **scaffold inicial**: um servidor Ktor mínimo com um endpoint `GET /health`, sem
autenticação, banco de dados, DTOs, provider ou qualquer lógica de análise/crédito. Essas peças
entram em fatias futuras, cada uma autorizada separadamente.

## Versões efetivas

- JDK: 21 (`jvmToolchain(21)` no `build.gradle.kts`)
- Gradle: 9.5.0 (via wrapper, `gradle/wrapper/gradle-wrapper.properties`)
- Kotlin: 2.2.10
- Ktor: 3.0.1
- Logback: 1.5.12

## Estrutura

```
src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/Application.kt
src/test/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/ApplicationTest.kt
```

Só o pacote `infrastructure` existe por enquanto (onde o servidor HTTP é montado). Pacotes
`application`/`domain` serão criados quando fatias futuras trouxerem lógica real — não foram
criados vazios nesta fatia.

## Rodando localmente

```
./gradlew.bat run
```

Sobe o servidor em `http://localhost:8080`; `GET /health` responde `200 OK` com corpo `OK`.

## Testes

```
./gradlew.bat test
```

Usa `testApplication` do Ktor (`ktor-server-test-host`) — sem subir uma porta de rede real, sem
dependência externa.
