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
import java.io.File

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

    // Mesmo molde como JSON Schema: o llama.cpp só deixa o modelo escrever JSON válido com essas chaves
    private const val ESQUEMA = """{"type":"object","properties":{
"cliente":{"type":"string"},"cnpj_cpf":{"type":"string"},"endereco":{"type":"string"},
"contato":{"type":"string"},"descricao_servico":{"type":"string"},
"itens":{"type":"array","items":{"type":"string"}},
"valor_total":{"type":"string"},"forma_pagamento":{"type":"string"},"observacoes":{"type":"string"}},
"required":["cliente","cnpj_cpf","endereco","contato","descricao_servico","itens","valor_total","forma_pagamento","observacoes"],
"additionalProperties":false}"""

    /** Última resposta crua do modelo (aparece na tela se nada for preenchido). */
    @Volatile var ultimaResposta: String = ""
        private set

    suspend fun extrair(ctx: Context, mensagem: String): Orcamento = withContext(Dispatchers.Default) {
        val json = if (modeloDisponivel(ctx)) {
            val bruto = trava.withLock {
                if (!carregado) {
                    LlamaBridge.updateGenerateParams(
                        temperature = 0f,      // NuExtract deve rodar sem aleatoriedade
                        maxTokens = 700,
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
                LlamaBridge.generateJson(prompt(mensagem), ESQUEMA)
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
        // Valor: o que estiver escrito "R$ ..." na mensagem vale mais que a IA
        val valorRegex = reValor.findAll(mensagem)
            .mapNotNull { Dinheiro.paraNumero(it.groupValues[1]) }
            .maxOrNull()
        val valor = valorRegex?.let { Dinheiro.formatar(it) }
            ?: Dinheiro.formatarTexto(texto(j, "valor_total"))

        val documento = reCnpj.find(mensagem)?.value
            ?: reCpf.find(mensagem)?.value
            ?: texto(j, "cnpj_cpf")

        val contato = texto(j, "contato").ifBlank {
            listOfNotNull(reFone.find(mensagem)?.value, reEmail.find(mensagem)?.value).joinToString(" / ")
        }

        var cliente = texto(j, "cliente")
        if (documento.isNotBlank()) cliente = cliente.replace(documento, "")
        cliente = cliente.replace(Regex("""[(,/\s]*(CNPJ|CPF)\s*:?\s*\)?\s*$""", RegexOption.IGNORE_CASE), "")
            .trim(' ', ',', '.', '(', ')')

        val obs = listOf(texto(j, "forma_pagamento"), texto(j, "observacoes"))
            .filter { it.isNotBlank() }
            .map { frase(it) }
            .distinct()
            .let { lista -> lista.filter { a -> lista.none { b -> b != a && b.contains(a.trimEnd('.')) } } }
            .joinToString("\n")

        return Orcamento(
            cliente = cliente,
            documento = documento.trim(),
            endereco = texto(j, "endereco").trim(' ', ',', '.'),
            contato = contato,
            descricao = frase(texto(j, "descricao_servico")),
            itens = lista(j, "itens").joinToString("\n") { maiuscula(it.trim(' ', ',', '.', '\'', '"')) },
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
