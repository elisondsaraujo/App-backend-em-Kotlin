package com.vendaflow

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class ErroResposta(val erro: String)

fun main() {
    Database.init()

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        configureApp()
    }.start(wait = true)
}

fun Application.configureApp() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }

    install(StatusPages) {
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErroResposta("Corpo da requisição inválido."))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErroResposta("JSON inválido."))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Erro não tratado", cause)
            call.respond(HttpStatusCode.InternalServerError, ErroResposta("Erro interno."))
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        staticResources("/", "static")

        route("/api") {
            get("/resumo") {
                call.respond(Database.obterResumo())
            }

            get("/registros") {
                val query = call.request.queryParameters["q"] ?: ""
                val status = call.request.queryParameters["status"] ?: "Todos"
                val sort = call.request.queryParameters["sort"] ?: "recentes"
                call.respond(Database.listar(query, status, sort))
            }

            post("/registros") {
                val input = call.receive<RegistroInput>()
                val erro = validarInput(input)
                if (erro != null) {
                    call.respond(HttpStatusCode.BadRequest, ErroResposta(erro))
                    return@post
                }
                val criado = Database.inserir(input)
                call.respond(HttpStatusCode.Created, criado)
            }

            put("/registros/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErroResposta("ID inválido."))
                    return@put
                }
                val input = call.receive<RegistroInput>()
                val erro = validarInput(input)
                if (erro != null) {
                    call.respond(HttpStatusCode.BadRequest, ErroResposta(erro))
                    return@put
                }
                val atualizado = Database.atualizar(id, input)
                if (atualizado == null) {
                    call.respond(HttpStatusCode.NotFound, ErroResposta("Registro não encontrado."))
                } else {
                    call.respond(atualizado)
                }
            }

            delete("/registros/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErroResposta("ID inválido."))
                    return@delete
                }
                if (Database.excluir(id)) {
                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErroResposta("Registro não encontrado."))
                }
            }

            get("/exportar-csv") {
                val bytes = Database.exportarCsvBytes()
                val filename = "clientes-vendas-${LocalDate.now()}.csv"
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString()
                )
                call.respondBytes(bytes, ContentType.Text.CSV.withCharset(Charsets.UTF_8))
            }

            post("/restaurar-exemplos") {
                Database.restaurarExemplos()
                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            }
        }
    }
}

private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

private fun validarInput(input: RegistroInput): String? {
    if (input.nome.isBlank()) return "O nome é obrigatório."
    if (input.nome.length > 120) return "O nome deve ter no máximo 120 caracteres."
    if (!emailRegex.matches(input.email.trim()) || input.email.length > 160) return "Informe um e-mail válido."
    if (input.telefone.isBlank()) return "O telefone é obrigatório."
    if (input.telefone.length > 30) return "O telefone deve ter no máximo 30 caracteres."
    if (!input.valor.isFinite() || input.valor < 0) return "O valor não pode ser negativo."
    if (input.valor > 1_000_000_000) return "Valor muito alto."
    if (input.status !in Database.validStatuses) return "Status inválido."
    return null
}
