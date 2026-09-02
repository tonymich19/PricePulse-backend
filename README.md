# PricePulse-backend

Backend do PricePulse — repositório separado do app Android (`PricePulse`), conforme decisão de
topologia registrada em `receiptanalysis-slice-report.md` do repositório do app.

O backend já expõe o fluxo de análise de nota: autenticação Firebase, crédito, PostgreSQL,
chamada ao provider de IA e consulta de status. A chave da OpenAI existe apenas no processo do
backend; ela nunca pertence ao aplicativo Android.

## Versões efetivas

- JDK: 21 (`jvmToolchain(21)` no `build.gradle.kts`)
- Gradle: 9.5.0 (via wrapper, `gradle/wrapper/gradle-wrapper.properties`)
- Kotlin: 2.2.10
- Ktor: 3.0.1
- Logback: 1.5.12

## Ambiente local com Docker

Pré-requisitos: Docker Desktop em execução, uma chave de API da OpenAI e o arquivo JSON de uma
service account do mesmo projeto Firebase que emite o token do usuário de teste.

No PowerShell, dentro deste repositório, inicie o ambiente com:

```powershell
.\scripts\Start-LocalApi.ps1
```

O script pede os dois segredos sem os exibir, copia-os somente para `secrets/` (ignorado pelo Git),
cria o PostgreSQL local, compila a API, aplica as migrations e espera `GET /health` responder
`OK`. O PostgreSQL é local e usa credenciais apenas de desenvolvimento.

Para testar o endpoint real, é necessário um **Firebase ID token de um usuário de teste**. O JSON
da service account não é um token de usuário. Com uma imagem `.jpg`, `.jpeg` ou `.png` de até
5 MiB:

```powershell
.\scripts\Test-ReceiptAnalysis.ps1 -ImagePath 'C:\caminho\para\nota.jpg'
```

O script solicita o token de forma não exibida e envia `POST /v1/receipt-analyses` com uma chave
de idempotência nova. Uma solicitação válida pode gerar cobrança/crédito de análise pela OpenAI.

Para encerrar e manter os dados do PostgreSQL local:

```powershell
docker compose down
```

Para encerrar removendo também os dados locais:

```powershell
docker compose down --volumes
```

## Testes

```
./gradlew.bat test
```

Usa `testApplication` do Ktor (`ktor-server-test-host`) — sem subir uma porta de rede real, sem
dependência externa.
