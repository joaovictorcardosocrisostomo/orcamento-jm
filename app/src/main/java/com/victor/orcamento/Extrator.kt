package com.victor.orcamento

import android.content.Context
import android.net.Uri
import com.llamatik.core.platform.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Lê a mensagem e preenche o orçamento.
 * 1) NuExtract (offline) preenche um molde JSON.
 * 2) Regex confere o que tem formato fixo (valor, CNPJ/CPF, telefone).
 */
object Extrator {

    // Molde no formato do NuExtract 1.5: textos vazios e listas como []
    private const val MOLDE = """{
    "cliente": "",
    "cnpj_cpf": "",
    "endereco": "",
    "contato": "",
    "descricao_servico": "",
    "itens": [],
    "valor_total": "",
    "forma_pagamento": "",
    "observacoes": ""
}"""

    private val trava = Mutex()
    @Volatile private var carregado = false

    fun arquivoModelo(ctx: Context) = File(ctx.filesDir, "nuextract.gguf")

    fun modeloDisponivel(ctx: Context): Boolean =
        arquivoModelo(ctx).let { it.exists() && it.length() > 50_000_000 }

    // Modelo baixado na primeira abertura (Q4_K_M, ~400 MB)
    private const val URL_MODELO =
        "https://huggingface.co/mradermacher/NuExtract-1.5-tiny-GGUF/resolve/main/NuExtract-1.5-tiny.Q4_K_M.gguf"
    private const val TAMANHO_MODELO = 397_808_224L
    private const val SHA256_MODELO = "3c86a4fcb41c783d77f49ea0aa64c28ddf069f3966d661599c5b2e76e06cc188"
    const val MB_MODELO = 398

    /**
     * Baixa o modelo da internet. Se a conexão cair, continua de onde parou
     * (inclusive se o app for fechado e aberto de novo). Confere o arquivo no fim.
     */
    suspend fun baixarModelo(ctx: Context, progresso: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val parcial = File(ctx.filesDir, "nuextract.part")
        var falhas = 0
        while (parcial.length() < TAMANHO_MODELO) {
            try {
                val con = (URL(URL_MODELO).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    if (parcial.length() > 0) setRequestProperty("Range", "bytes=${parcial.length()}-")
                }
                val codigo = con.responseCode
                if (codigo != 200 && codigo != 206) error("o servidor respondeu $codigo")
                val continuar = codigo == 206
                var feito = if (continuar) parcial.length() else 0L
                con.inputStream.use { entrada ->
                    FileOutputStream(parcial, continuar).use { saida ->
                        val buf = ByteArray(1 shl 16)
                        var ultimo = -1
                        while (true) {
                            val n = entrada.read(buf)
                            if (n < 0) break
                            saida.write(buf, 0, n)
                            feito += n
                            val pct = (feito * 1000 / TAMANHO_MODELO).toInt()
                            if (pct != ultimo) { ultimo = pct; progresso(feito.toFloat() / TAMANHO_MODELO) }
                        }
                    }
                }
                con.disconnect()
                falhas = 0
            } catch (e: IOException) {
                if (++falhas > 5) throw IOException("sem conexão (${e.message})")
                delay(3_000)
            }
        }
        if (parcial.length() != TAMANHO_MODELO || sha256(parcial) != SHA256_MODELO) {
            parcial.delete()
            error("o arquivo veio corrompido, tente de novo")
        }
        arquivoModelo(ctx).delete()
        if (!parcial.renameTo(arquivoModelo(ctx))) error("não consegui salvar o modelo")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { e ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = e.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Baixa sozinho na primeira abertura, mas não depois que o usuário apagou o modelo de propósito. */
    fun deveBaixarSozinho(ctx: Context) =
        !ctx.getSharedPreferences("ia", Context.MODE_PRIVATE).getBoolean("trocouModelo", false)

    /** Apaga o modelo atual para escolher outro (ex.: trocar Q4 por Q8). */
    suspend fun esquecerModelo(ctx: Context) = withContext(Dispatchers.Default) {
        ctx.getSharedPreferences("ia", Context.MODE_PRIVATE).edit().putBoolean("trocouModelo", true).apply()
        trava.withLock {
            if (carregado) { LlamaBridge.shutdown(); carregado = false }
            arquivoModelo(ctx).delete()
        }
    }

    /** Copia o .gguf escolhido pelo usuário para dentro do app (uma vez só). */
    suspend fun importarModelo(ctx: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val destino = arquivoModelo(ctx)
        val temp = File(ctx.filesDir, "nuextract.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { entrada ->
            temp.outputStream().use { saida -> entrada.copyTo(saida, 1 shl 20) }
        }
        destino.delete()
        if (!temp.renameTo(destino)) error("Não consegui salvar o modelo")
    }

    /** Última resposta crua do modelo (aparece na tela se nada for preenchido). */
    @Volatile var ultimaResposta: String = ""
        private set

    suspend fun extrair(ctx: Context, mensagem: String): Orcamento = withContext(Dispatchers.Default) {
        val json = if (modeloDisponivel(ctx)) {
            val bruto = trava.withLock {
                if (!carregado) {
                    LlamaBridge.updateGenerateParams(
                        temperature = 0f,      // NuExtract deve rodar sem aleatoriedade
                        maxTokens = 450,
                        topP = 1f,
                        topK = 1,
                        repeatPenalty = 1f,    // ele copia trechos do texto; não penalizar repetição
                        contextLength = 2048,
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                        useMmap = true,
                        flashAttention = false,
                        batchSize = 512,
                    )
                    carregado = LlamaBridge.initGenerateModel(arquivoModelo(ctx).absolutePath)
                    if (!carregado) error("Não consegui carregar o modelo")
                }
                LlamaBridge.generate(prompt(mensagem))
            }
            ultimaResposta = bruto
            lerJson(bruto)
        } else {
            JSONObject() // sem modelo: só as regras abaixo
        }
        montar(mensagem, json)
    }

    /**
     * Formato do NuExtract. A Llamatik embrulha qualquer prompt num formato de chat
     * (que o NuExtract não entende), a menos que o texto contenha "user\n".
     * Por isso a primeira linha é "user": assim o prompt vai cru para o modelo.
     */
    private fun prompt(texto: String) =
        "user\n<|input|>\n### Template:\n$MOLDE\n### Text:\n${texto.trim()}\n\n<|output|>"

    /** Pega o primeiro objeto JSON completo (contando chaves, ignorando as que estão dentro de textos). */
    private fun lerJson(bruto: String): JSONObject {
        val t = bruto.substringAfterLast("<|output|>")
        val ini = t.indexOf('{')
        if (ini < 0) return JSONObject()
        var nivel = 0
        var emTexto = false
        var escape = false
        for (i in ini until t.length) {
            val c = t[i]
            if (emTexto) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> emTexto = false
                }
                continue
            }
            when (c) {
                '"' -> emTexto = true
                '{' -> nivel++
                '}' -> if (--nivel == 0) {
                    return try { JSONObject(t.substring(ini, i + 1)) } catch (e: Exception) { JSONObject() }
                }
            }
        }
        return JSONObject()
    }

    // ---------- regras fixas ----------

    private val reValor = Regex("""R\$\s*([\d.]+(?:,\d{1,2})?)""")
    private val reCnpj = Regex("""\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2}""")
    private val reCpf = Regex("""\b\d{3}\.\d{3}\.\d{3}-\d{2}\b""")
    private val reFone = Regex("""\(?\b\d{2}\)?\s?9?\s?\d{4}[-\s]?\d{4}\b""")
    private val reEmail = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")

    fun montar(mensagem: String, j: JSONObject): Orcamento {
        // Troca o que a IA escreveu pelo trecho igual da mensagem (corrige acentos trocados)
        fun t(chave: String) = Corretor.alinhar(mensagem, texto(j, chave))
        // Valor: o que estiver escrito "R$ ..." na mensagem vale mais que a IA
        val valorRegex = reValor.findAll(mensagem)
            .mapNotNull { Dinheiro.paraNumero(it.groupValues[1]) }
            .maxOrNull()
        val valor = valorRegex?.let { Dinheiro.formatar(it) }
            ?: Dinheiro.formatarTexto(texto(j, "valor_total"))

        val documento = reCnpj.find(mensagem)?.value
            ?: reCpf.find(mensagem)?.value
            ?: texto(j, "cnpj_cpf")

        val contato = t("contato").ifBlank {
            listOfNotNull(reFone.find(mensagem)?.value, reEmail.find(mensagem)?.value).joinToString(" / ")
        }

        var cliente = t("cliente").ifBlank { Corretor.clientePorRegra(mensagem) }
        if (documento.isNotBlank()) cliente = cliente.replace(documento, "")
        cliente = cliente.replace(Regex("""[(,/\s]*(CNPJ|CPF)\s*:?\s*\)?\s*$""", RegexOption.IGNORE_CASE), "")
            .trim(' ', ',', '.', '(', ')')

        val obs = listOf(t("forma_pagamento"), t("observacoes"))
            .filter { it.isNotBlank() }
            .map { frase(it) }
            .distinct()
            .let { lista -> lista.filter { a -> lista.none { b -> b != a && b.contains(a.trimEnd('.')) } } }
            .joinToString("\n")

        return Orcamento(
            cliente = cliente,
            documento = documento.trim(),
            endereco = t("endereco").trim(' ', ',', '.'),
            contato = contato,
            descricao = frase(t("descricao_servico")),
            itens = Corretor.separarItens(lista(j, "itens").map { Corretor.alinhar(mensagem, it) })
                .joinToString("\n") { maiuscula(it.trim(' ', ',', '.', '\'', '"')) },
            valor = valor,
            observacoes = obs,
        )
    }

    private fun texto(j: JSONObject, chave: String): String {
        val v = j.opt(chave) ?: return ""
        return when (v) {
            is JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }
            JSONObject.NULL -> ""
            else -> v.toString()
        }.trim()
    }

    private fun lista(j: JSONObject, chave: String): List<String> {
        val v = j.opt(chave)
        val itens = when (v) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            is String -> v.split(Regex("""[,;\n]"""))
            else -> emptyList()
        }
        return itens.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun maiuscula(s: String) = s.replaceFirstChar { it.uppercase() }

    /** Primeira letra maiúscula e ponto final. */
    private fun frase(s: String): String {
        val t = s.trim().trim('"', '\'')
        if (t.isEmpty()) return ""
        val m = maiuscula(t)
        return if (m.last() in ".!?)") m else "$m."
    }
}
