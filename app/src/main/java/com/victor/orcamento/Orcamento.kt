package com.victor.orcamento

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Tudo o que vai no PDF. Os itens ficam um por linha. */
data class Orcamento(
    val cliente: String = "",
    val documento: String = "",   // CNPJ ou CPF do cliente
    val endereco: String = "",
    val contato: String = "",
    val descricao: String = "",
    val itens: String = "",
    val valor: String = "",
    val observacoes: String = "",
) {
    fun listaItens(): List<String> =
        itens.lines().map { it.trim().trimStart('-', '•', '*').trim() }.filter { it.isNotEmpty() }
}

object Dinheiro {
    private val brasil: Locale = Locale.forLanguageTag("pt-BR")

    /** Aceita "R$ 22.000,00", "22000", "7.300", "1800,5"… */
    fun paraNumero(texto: String): BigDecimal? {
        val limpo = texto.replace(Regex("[^0-9,.]"), "").trim('.', ',')
        if (limpo.isEmpty()) return null
        val normal = when {
            limpo.contains(',') -> limpo.replace(".", "").replace(',', '.')
            Regex("^\\d{1,3}(\\.\\d{3})+$").matches(limpo) -> limpo.replace(".", "")
            else -> limpo
        }
        return normal.toBigDecimalOrNull()
    }

    fun formatar(valor: BigDecimal): String =
        NumberFormat.getCurrencyInstance(brasil).format(valor).replace(' ', ' ')

    /** "22000" -> "R$ 22.000,00". Se não der para entender, devolve o texto como está. */
    fun formatarTexto(texto: String): String =
        paraNumero(texto)?.let { formatar(it) } ?: texto.trim()
}
