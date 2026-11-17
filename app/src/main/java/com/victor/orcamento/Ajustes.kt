package com.victor.orcamento

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** Dados fixos da empresa que aparecem no cabeçalho do PDF. */
data class Empresa(
    val sigla: String = "JM",
    val subtitulo: String = "MANTA ASFÁLTICA",
    val cnpj: String = "43.188.814/0001-04",
    val telefone: String = "85 92178-5757",
    val endereco: String = "Rua Alameda Jasmim, 894, Vila Velha",
)

object Ajustes {
    private const val PREFS = "empresa"

    fun carregar(ctx: Context): Empresa {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val padrao = Empresa()
        return Empresa(
            sigla = p.getString("sigla", padrao.sigla)!!,
            subtitulo = p.getString("subtitulo", padrao.subtitulo)!!,
            cnpj = p.getString("cnpj", padrao.cnpj)!!,
            telefone = p.getString("telefone", padrao.telefone)!!,
            endereco = p.getString("endereco", padrao.endereco)!!,
        )
    }

    fun salvar(ctx: Context, e: Empresa) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("sigla", e.sigla)
            .putString("subtitulo", e.subtitulo)
            .putString("cnpj", e.cnpj)
            .putString("telefone", e.telefone)
            .putString("endereco", e.endereco)
            .apply()
    }

    fun arquivoLogo(ctx: Context) = File(ctx.filesDir, "logo.png")
    fun arquivoAssinatura(ctx: Context) = File(ctx.filesDir, "assinatura.png")

    /** Copia a imagem escolhida, reduzida (para o PDF não ficar pesado). */
    fun importarImagem(ctx: Context, uri: Uri, destino: File) {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
        var amostra = 1
        while (maxOf(limites.outWidth, limites.outHeight) / amostra > 1200) amostra *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = amostra })
        } ?: error("Não consegui abrir a imagem")
        destino.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
