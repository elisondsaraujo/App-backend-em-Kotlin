# App-backend-em-Kotlin
backend em Kotlin com ktor e SQLite.

Produção: https://app-backend-em-kotlin-production.up.railway.app
necessário JDK 17+ e o Gradle instalado.
raiz da pasta vendaflow-sqlite/, execute o comando: Bash <>
gradle run

## Configuração

- `PORT` (padrão 8080)
- `DB_PATH` (padrão `vendaflow.db`): aponte para um volume para persistir os dados.
- `GET /health` para healthcheck.
