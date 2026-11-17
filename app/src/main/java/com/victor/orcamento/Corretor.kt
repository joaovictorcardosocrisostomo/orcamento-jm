package com.victor.orcamento

/**
 * O NuExtract copia trechos da mensagem, mas às vezes troca acentos e letras
 * ("Máo de obra", "asfótica"). Aqui cada valor extraído é trocado pelo trecho
 * mais parecido da mensagem original, que está escrito certo.
 */
object Corretor {

    private val palavra = Regex("""\S+""")
    private const val PONTAS = " .,;:'\"“”"

    fun alinhar(mensagem: String, valor: String, minimo: Double = 0.7): String {
        val v = valor.trim()
        if (v.isEmpty()) return v
        val pal = palavra.findAll(mensagem).map { it.range }.toList()
        val n = v.split(Regex("""\s+""")).size
        val vl = v.lowercase()
        var melhor = 0.0
        var escolhido = v
        for (tam in maxOf(1, n - 2)..n + 2) {
            for (i in 0..pal.size - tam) {
                val c = mensagem.substring(pal[i].first, pal[i + tam - 1].last + 1).trim { it in PONTAS }
                if (kotlin.math.abs(c.length - v.length) > maxOf(6.0, v.length * 0.3)) continue
                val nota = 1.0 - distancia(vl, c.lowercase()).toDouble() / maxOf(c.length, v.length)
                if (nota > melhor) { melhor = nota; escolhido = c }
            }
        }
        return if (melhor >= minimo) escolhido else v
    }

    /** Separa "Carga de gás e Mão de obra" em dois itens (só quando o que vem depois do "e" parece outro item). */
    private val separador = Regex("""\s+e\s+(?=\d|[A-ZÀ-Ú]|[Mm][ãa]o de obra)""")

    fun separarItens(itens: List<String>): List<String> =
        itens.flatMap { it.split(separador) }.map { it.trim() }.filter { it.isNotEmpty() }

    /** Nome do cliente quando a IA não achou: "para o Condomínio X", "cliente Ana Paula"… */
    fun clientePorRegra(mensagem: String): String {
        val re = Regex("""(?:cliente|para o|para a|pro|pra)\s+(?:cliente\s+)?([A-ZÀ-Ú][^,.;()\n]*)""")
        return re.find(mensagem)?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun distancia(a: String, b: String): Int {
        var ant = IntArray(b.length + 1) { it }
        var atual = IntArray(b.length + 1)
        for (i in 1..a.length) {
            atual[0] = i
            for (j in 1..b.length) {
                val custo = if (a[i - 1] == b[j - 1]) 0 else 1
                atual[j] = minOf(ant[j] + 1, atual[j - 1] + 1, ant[j - 1] + custo)
            }
            val t = ant; ant = atual; atual = t
        }
        return ant[b.length]
    }
}
