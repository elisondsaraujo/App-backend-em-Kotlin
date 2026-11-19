package com.vendaflow

import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class RegistroVenda(
    val id: Long,
    val nome: String,
    val email: String,
    val telefone: String,
    val valor: Double,
    val status: String,
    val createdAt: Long
)

@Serializable
data class RegistroInput(
    val nome: String,
    val email: String,
    val telefone: String,
    val valor: Double,
    val status: String
)

@Serializable
data class StatusResumo(
    val status: String,
    val quantidade: Int,
    val totalValor: Double
)

@Serializable
data class ResumoDashboard(
    val receitaConcluida: Double,
    val qtdConcluida: Int,
    val valorEmAberto: Double,
    val qtdEmAberto: Int,
    val totalRegistros: Int,
    val somaGeral: Double,
    val ticketMedio: Double,
    val porStatus: List<StatusResumo>,
    val recentes: List<RegistroVenda>
)

object Database {
    private const val DB_URL = "jdbc:sqlite:vendaflow.db"
    private val lock = Any()

    val validStatuses = listOf("Concluída", "Pendente", "Em Negociação", "Cancelada")

    private fun <T> withConnection(block: (Connection) -> T): T = synchronized(lock) {
        DriverManager.getConnection(DB_URL).use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout = 5000")
            }
            block(conn)
        }
    }

    fun init() {
        withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode = WAL")
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS registros (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        nome TEXT NOT NULL,
                        email TEXT NOT NULL,
                        telefone TEXT NOT NULL,
                        valor REAL NOT NULL,
                        status TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS app_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }

            val alreadySeeded = conn.prepareStatement("SELECT value FROM app_meta WHERE key = 'seeded'").use { ps ->
                ps.executeQuery().use { rs -> rs.next() }
            }

            if (!alreadySeeded) {
                insertSampleRecords(conn)
                conn.prepareStatement("INSERT INTO app_meta (key, value) VALUES ('seeded', '1')").use { ps ->
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun insertSampleRecords(conn: Connection) {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val samples = listOf(
            RegistroVenda(0, "Ana Paula Ferreira", "ana.ferreira@techsul.com.br", "(11) 98765-4321", 4850.00, "Concluída", now - day * 5),
            RegistroVenda(0, "Bruno Mendes Costa", "bruno@costalogistica.com", "(21) 99412-8833", 2300.50, "Em Negociação", now - day * 4),
            RegistroVenda(0, "Carla Ribeiro Silva", "carla.ribeiro@gmail.com", "(31) 99123-4567", 1290.00, "Pendente", now - day * 3),
            RegistroVenda(0, "Diego Almeida Rocha", "diego@rochaarquitetura.br", "(41) 98845-1020", 7600.00, "Concluída", now - day * 2),
            RegistroVenda(0, "Fernanda Lima Santos", "fernanda.lima@inovare.io", "(51) 99654-3210", 980.00, "Cancelada", now - day * 1),
            RegistroVenda(0, "Gustavo Henrique Alves", "gustavo@alvescomercial.com", "(11) 97732-1190", 3450.00, "Concluída", now - 21_600_000L)
        )

        val sql = "INSERT INTO registros (nome, email, telefone, valor, status, created_at) VALUES (?, ?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            for (s in samples) {
                ps.setString(1, s.nome)
                ps.setString(2, s.email)
                ps.setString(3, s.telefone)
                ps.setDouble(4, s.valor)
                ps.setString(5, s.status)
                ps.setLong(6, s.createdAt)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun restaurarExemplos() {
        withConnection { conn ->
            conn.createStatement().use { st -> st.execute("DELETE FROM registros") }
            insertSampleRecords(conn)
        }
    }

    fun listar(query: String, status: String, sort: String): List<RegistroVenda> = withConnection { conn ->
        val sql = StringBuilder("SELECT id, nome, email, telefone, valor, status, created_at FROM registros WHERE 1=1")
        val params = mutableListOf<String>()

        if (query.isNotBlank()) {
            sql.append(" AND (LOWER(nome) LIKE ? OR LOWER(email) LIKE ? OR LOWER(telefone) LIKE ?)")
            val pattern = "%${query.trim().lowercase()}%"
            params.add(pattern)
            params.add(pattern)
            params.add(pattern)
        }

        if (status.isNotBlank() && status != "Todos") {
            sql.append(" AND status = ?")
            params.add(status)
        }

        val orderClause = when (sort) {
            "maior-valor" -> "valor DESC, id DESC"
            "menor-valor" -> "valor ASC, id DESC"
            "nome-az" -> "LOWER(nome) ASC, id DESC"
            else -> "created_at DESC, id DESC"
        }
        sql.append(" ORDER BY $orderClause")

        conn.prepareStatement(sql.toString()).use { ps ->
            params.forEachIndexed { idx, value -> ps.setString(idx + 1, value) }
            ps.executeQuery().use { rs ->
                val list = mutableListOf<RegistroVenda>()
                while (rs.next()) {
                    list.add(
                        RegistroVenda(
                            id = rs.getLong("id"),
                            nome = rs.getString("nome"),
                            email = rs.getString("email"),
                            telefone = rs.getString("telefone"),
                            valor = rs.getDouble("valor"),
                            status = rs.getString("status"),
                            createdAt = rs.getLong("created_at")
                        )
                    )
                }
                list
            }
        }
    }

    fun inserir(input: RegistroInput): RegistroVenda = withConnection { conn ->
        val now = System.currentTimeMillis()
        val sql = "INSERT INTO registros (nome, email, telefone, valor, status, created_at) VALUES (?, ?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, input.nome.trim())
            ps.setString(2, input.email.trim())
            ps.setString(3, input.telefone.trim())
            ps.setDouble(4, input.valor)
            ps.setString(5, input.status)
            ps.setLong(6, now)
            ps.executeUpdate()
        }

        val newId = conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

        RegistroVenda(
            id = newId,
            nome = input.nome.trim(),
            email = input.email.trim(),
            telefone = input.telefone.trim(),
            valor = input.valor,
            status = input.status,
            createdAt = now
        )
    }

    fun atualizar(id: Long, input: RegistroInput): RegistroVenda? = withConnection { conn ->
        val sql = "UPDATE registros SET nome = ?, email = ?, telefone = ?, valor = ?, status = ? WHERE id = ?"
        val affected = conn.prepareStatement(sql).use { ps ->
            ps.setString(1, input.nome.trim())
            ps.setString(2, input.email.trim())
            ps.setString(3, input.telefone.trim())
            ps.setDouble(4, input.valor)
            ps.setString(5, input.status)
            ps.setLong(6, id)
            ps.executeUpdate()
        }
        if (affected == 0) return@withConnection null

        conn.prepareStatement("SELECT id, nome, email, telefone, valor, status, created_at FROM registros WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else RegistroVenda(
                    id = rs.getLong("id"),
                    nome = rs.getString("nome"),
                    email = rs.getString("email"),
                    telefone = rs.getString("telefone"),
                    valor = rs.getDouble("valor"),
                    status = rs.getString("status"),
                    createdAt = rs.getLong("created_at")
                )
            }
        }
    }

    fun excluir(id: Long): Boolean = withConnection { conn ->
        conn.prepareStatement("DELETE FROM registros WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate() > 0
        }
    }

    fun obterResumo(): ResumoDashboard = withConnection { conn ->
        val statsMap = mutableMapOf<String, Pair<Int, Double>>()
        val groupSql = "SELECT status, COUNT(*) AS qtd, COALESCE(SUM(valor), 0.0) AS total FROM registros GROUP BY status"
        conn.createStatement().use { st ->
            st.executeQuery(groupSql).use { rs ->
                while (rs.next()) {
                    statsMap[rs.getString("status")] = rs.getInt("qtd") to rs.getDouble("total")
                }
            }
        }

        val porStatus = validStatuses.map { st ->
            val (qtd, total) = statsMap[st] ?: (0 to 0.0)
            StatusResumo(status = st, quantidade = qtd, totalValor = total)
        }

        val concluida = porStatus.first { it.status == "Concluída" }
        val pendente = porStatus.first { it.status == "Pendente" }
        val negociacao = porStatus.first { it.status == "Em Negociação" }
        val cancelada = porStatus.first { it.status == "Cancelada" }

        val totalRegistros = porStatus.sumOf { it.quantidade }
        val somaGeral = porStatus.sumOf { it.totalValor }
        val qtdEmAberto = pendente.quantidade + negociacao.quantidade
        val valorEmAberto = pendente.totalValor + negociacao.totalValor
        val qtdAtivos = totalRegistros - cancelada.quantidade
        val valorAtivos = somaGeral - cancelada.totalValor
        val ticketMedio = if (qtdAtivos > 0) valorAtivos / qtdAtivos else 0.0

        val recentes = mutableListOf<RegistroVenda>()
        val recentSql = "SELECT id, nome, email, telefone, valor, status, created_at FROM registros ORDER BY created_at DESC, id DESC LIMIT 5"
        conn.createStatement().use { st ->
            st.executeQuery(recentSql).use { rs ->
                while (rs.next()) {
                    recentes.add(
                        RegistroVenda(
                            id = rs.getLong("id"),
                            nome = rs.getString("nome"),
                            email = rs.getString("email"),
                            telefone = rs.getString("telefone"),
                            valor = rs.getDouble("valor"),
                            status = rs.getString("status"),
                            createdAt = rs.getLong("created_at")
                        )
                    )
                }
            }
        }

        ResumoDashboard(
            receitaConcluida = concluida.totalValor,
            qtdConcluida = concluida.quantidade,
            valorEmAberto = valorEmAberto,
            qtdEmAberto = qtdEmAberto,
            totalRegistros = totalRegistros,
            somaGeral = somaGeral,
            ticketMedio = ticketMedio,
            porStatus = porStatus,
            recentes = recentes
        )
    }

    fun exportarCsvBytes(): ByteArray {
        val todos = listar(query = "", status = "Todos", sort = "recentes")
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))

        fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""

        val header = listOf("ID", "Nome", "E-mail", "Telefone", "Valor (R$)", "Status", "Data de Cadastro")
            .joinToString(";") { csvCell(it) }

        val rows = todos.joinToString("\r\n") { r ->
            val valorFormatado = String.format(Locale("pt", "BR"), "%.2f", r.valor)
            listOf(
                r.id.toString(),
                r.nome,
                r.email,
                r.telefone,
                valorFormatado,
                r.status,
                sdf.format(Date(r.createdAt))
            ).joinToString(";") { csvCell(it) }
        }

        val csvString = "$header\r\n$rows"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        return bom + csvString.toByteArray(Charsets.UTF_8)
    }
}
