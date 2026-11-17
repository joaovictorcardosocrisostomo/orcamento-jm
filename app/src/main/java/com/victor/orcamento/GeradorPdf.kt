package com.victor.orcamento

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Desenha o orçamento numa folha A4 (595 x 842 pontos), no layout do modelo JM. */
object GeradorPdf {

    private const val LARG = 595
    private const val ALT = 842
    private const val ESQ = 26f
    private const val DIR = 569f
    private const val LINHA = 18f
    private const val LINHAS_TABELA = 12

    private val VERMELHO = Color.rgb(220, 30, 30)

    fun gerar(ctx: Context, o: Orcamento, e: Empresa): File {
        val doc = PdfDocument()
        val pagina = doc.startPage(PdfDocument.PageInfo.Builder(LARG, ALT, 1).create())
        desenhar(pagina.canvas, o, e,
            logo = carregar(Ajustes.arquivoLogo(ctx)),
            assinatura = carregar(Ajustes.arquivoAssinatura(ctx)))
        doc.finishPage(pagina)

        val pasta = File(ctx.cacheDir, "orcamentos").apply { mkdirs() }
        pasta.listFiles()?.forEach { it.delete() } // guarda só o último
        val nome = o.cliente.ifBlank { "cliente" }
            .replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().replace(' ', '_').take(40)
        val data = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val arquivo = File(pasta, "Orcamento_${nome}_$data.pdf")
        arquivo.outputStream().use { doc.writeTo(it) }
        doc.close()
        return arquivo
    }

    private fun carregar(f: File): Bitmap? = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null

    // ---------------- desenho ----------------

    private fun texto(tamanho: Float, negrito: Boolean = false, cor: Int = Color.BLACK) = TextPaint().apply {
        isAntiAlias = true
        textSize = tamanho
        color = cor
        typeface = if (negrito) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private val traco = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = Color.BLACK; isAntiAlias = true }
    private val tracoGrosso = Paint(traco).apply { strokeWidth = 1.6f }

    private fun desenhar(c: Canvas, o: Orcamento, e: Empresa, logo: Bitmap?, assinatura: Bitmap?) {
        // ---- cabeçalho ----
        if (logo != null) {
            imagemCabendo(c, logo, RectF(30f, 18f, 145f, 92f))
        } else {
            val sigla = texto(44f, negrito = true, cor = VERMELHO)
            encolher(sigla, e.sigla, 110f)
            c.drawText(e.sigla, 87f - sigla.measureText(e.sigla) / 2, 66f, sigla)
            val sub = texto(8.5f)
            encolher(sub, e.subtitulo, 120f)
            c.drawText(e.subtitulo, 87f - sub.measureText(e.subtitulo) / 2, 84f, sub)
        }
        val info = texto(10.5f)
        val larguraInfo = 235f
        linhaCabendo(c, if (e.cnpj.isBlank()) "" else "CNPJ: ${e.cnpj}", 162f, 42f, larguraInfo, info)
        linhaCabendo(c, if (e.telefone.isBlank()) "" else "WhatsApp: ${e.telefone}", 162f, 62f, larguraInfo, info)
        linhaCabendo(c, e.endereco, 162f, 82f, larguraInfo, info)

        // caixa do valor final
        c.drawRoundRect(RectF(405f, 24f, 566f, 88f), 5f, 5f, tracoGrosso)
        c.drawText("Valor Final", 414f, 44f, texto(10.5f))
        linhaCabendo(c, o.valor, 414f, 72f, 145f, texto(16f))
        c.drawLine(0f, 101f, LARG.toFloat(), 101f, tracoGrosso)

        // ---- título ----
        val titulo = texto(12.5f, negrito = true)
        c.drawText("ORÇAMENTO", LARG / 2f - titulo.measureText("ORÇAMENTO") / 2, 128f, titulo)

        // ---- dados do cliente ----
        var y = 140f
        val rotulo = texto(10.5f, negrito = true)
        val normal = texto(10.5f)
        val doc = o.documento.trim()
        val tipoDoc = if (doc.count { it.isDigit() } == 11) "CPF" else "CNPJ"
        val clienteTxt = o.cliente + if (doc.isNotBlank()) " / $tipoDoc: $doc" else ""
        y = linhaRotulada(c, y, "Cliente:", clienteTxt, rotulo, normal)
        if (o.endereco.isNotBlank()) y = linhaRotulada(c, y, "Endereço:", o.endereco, rotulo, normal)
        if (o.contato.isNotBlank() || o.endereco.isBlank())
            y = linhaRotulada(c, y, if (o.contato.isBlank()) "Contato (Telefone ou Email):" else "Contato:", o.contato, rotulo, normal)

        // descrição do serviço
        val fimDescricao = 382f
        c.drawRect(ESQ, y, DIR, fimDescricao, traco)
        c.drawText("Descrição do Serviço:", ESQ + 6, y + 15, rotulo)
        blocoTexto(c, o.descricao, ESQ + 6, y + 22, DIR - ESQ - 12, fimDescricao - y - 26, texto(10.5f))

        // ---- tabela de itens ----
        val topo = 392f
        val fimTabela = topo + LINHA * (LINHAS_TABELA + 1)
        c.drawRect(ESQ, topo, DIR, fimTabela, traco)
        for (i in 1..LINHAS_TABELA) c.drawLine(ESQ, topo + LINHA * i, DIR, topo + LINHA * i, traco)
        c.drawText("Quantidade e Descrição", ESQ + 6, topo + 13, normal)
        direita(c, "Valor Unitário", 478f, topo + 13, normal)
        direita(c, "Valor Total", DIR - 6, topo + 13, normal)

        val itens = o.listaItens().take(LINHAS_TABELA - 1)
        itens.forEachIndexed { i, item ->
            linhaCabendo(c, item, ESQ + 6, topo + LINHA * (i + 1) + 13, 380f, texto(10.5f))
        }
        // com 1 item o total vai na mesma linha; com vários, numa linha logo abaixo
        val linhaTotal = if (itens.size <= 1) 1 else itens.size + 1
        direita(c, o.valor, DIR - 6, topo + LINHA * linhaTotal + 13, normal)

        // ---- observações ----
        val topoObs = fimTabela + 10
        val fimObs = 745f
        c.drawRect(ESQ, topoObs, DIR, fimObs, traco)
        c.drawText("Observações:", ESQ + 6, topoObs + 15, rotulo)
        blocoTexto(c, o.observacoes, ESQ + 6, topoObs + 22, DIR - ESQ - 12, fimObs - topoObs - 26, texto(10.5f))

        // ---- assinatura ----
        if (assinatura != null) imagemCabendo(c, assinatura, RectF(215f, 752f, 380f, 793f))
        c.drawLine(190f, 795f, 405f, 795f, tracoGrosso)
        val ass = texto(10.5f)
        c.drawText("Ass. Téc. Responsável", LARG / 2f - ass.measureText("Ass. Téc. Responsável") / 2, 811f, ass)
    }

    private fun linhaRotulada(c: Canvas, y: Float, rotulo: String, valor: String, pr: TextPaint, pn: TextPaint): Float {
        c.drawRect(ESQ, y, DIR, y + LINHA, traco)
        c.drawText(rotulo, ESQ + 6, y + 13, pr)
        val x = ESQ + 6 + pr.measureText(rotulo) + 4
        linhaCabendo(c, valor, x, y + 13, DIR - 6 - x, pn)
        return y + LINHA
    }

    private fun direita(c: Canvas, s: String, xDireita: Float, y: Float, p: TextPaint) {
        c.drawText(s, xDireita - p.measureText(s), y, p)
    }

    /** Uma linha só: diminui a fonte até caber. */
    private fun linhaCabendo(c: Canvas, s: String, x: Float, y: Float, largura: Float, p: TextPaint) {
        if (s.isBlank()) return
        val copia = TextPaint(p)
        encolher(copia, s, largura)
        c.drawText(s, x, y, copia)
    }

    private fun encolher(p: Paint, s: String, largura: Float) {
        while (p.measureText(s) > largura && p.textSize > 6f) p.textSize -= 0.5f
    }

    /** Texto com quebra de linha, cortado na altura da caixa. */
    private fun blocoTexto(c: Canvas, s: String, x: Float, y: Float, largura: Float, altura: Float, p: TextPaint) {
        if (s.isBlank()) return
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, largura.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(1.5f, 1f)
            .build()
        c.save()
        c.translate(x, y)
        c.clipRect(0f, 0f, largura, altura)
        layout.draw(c)
        c.restore()
    }

    private fun imagemCabendo(c: Canvas, bmp: Bitmap, caixa: RectF) {
        val escala = minOf(caixa.width() / bmp.width, caixa.height() / bmp.height)
        val w = bmp.width * escala
        val h = bmp.height * escala
        val destino = RectF(caixa.centerX() - w / 2, caixa.centerY() - h / 2, caixa.centerX() + w / 2, caixa.centerY() + h / 2)
        c.drawBitmap(bmp, null, destino, Paint(Paint.FILTER_BITMAP_FLAG))
    }
}
