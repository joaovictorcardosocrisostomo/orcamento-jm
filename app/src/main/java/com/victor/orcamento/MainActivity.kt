package com.victor.orcamento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val textoRecebido = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lerCompartilhamento(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { App(textoRecebido) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lerCompartilhamento(intent)
    }

    /** Texto vindo do "Compartilhar" do WhatsApp. */
    private fun lerCompartilhamento(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            textoRecebido.value = i.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}

@Composable
private fun App(textoRecebido: MutableState<String?>) {
    var telaAjustes by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (telaAjustes) {
            BackHandler { telaAjustes = false }
            TelaAjustes(aoSair = { telaAjustes = false })
        } else {
            TelaPrincipal(textoRecebido, abrirAjustes = { telaAjustes = true })
        }
    }
}

@Composable
private fun TelaPrincipal(textoRecebido: MutableState<String?>, abrirAjustes: () -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()

    var mensagem by remember { mutableStateOf("") }
    var orc by remember { mutableStateOf(Orcamento()) }
    var ocupado by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf("") }
    var modeloOk by remember { mutableStateOf(Extrator.modeloDisponivel(ctx)) }

    // Primeira abertura: baixa a IA sozinho
    LaunchedEffect(Unit) {
        if (!modeloOk && Extrator.deveBaixarSozinho(ctx)) Baixador.iniciar(ctx)
    }
    // Quando o download termina, confere se o modelo chegou
    LaunchedEffect(Baixador.progresso) {
        if (Baixador.progresso == null) modeloOk = Extrator.modeloDisponivel(ctx)
    }

    LaunchedEffect(textoRecebido.value) {
        textoRecebido.value?.let { mensagem = it; textoRecebido.value = null }
    }

    val escolherModelo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) escopo.launch {
            ocupado = true; aviso = "Copiando o modelo (só desta vez)…"
            aviso = try {
                Extrator.importarModelo(ctx, uri); modeloOk = Extrator.modeloDisponivel(ctx)
                if (modeloOk) "Modelo pronto." else "Esse arquivo não parece ser o modelo .gguf."
            } catch (e: Exception) { "Erro ao copiar: ${e.message}" }
            ocupado = false
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Orçamento", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = abrirAjustes) { Text("Ajustes") }
    }

    if (!modeloOk) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = Baixador.progresso
                if (p != null) {
                    Text("Baixando a IA… ${(p * 100).toInt()}% de ${Extrator.MB_MODELO} MB. Só na primeira vez; deixe o app aberto.")
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("A IA ainda não está no celular (${Extrator.MB_MODELO} MB, baixa uma vez só). Sem ela, o app só preenche valor e CNPJ.")
                    if (Baixador.erro.isNotBlank()) Text(Baixador.erro, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { Baixador.iniciar(ctx) }, enabled = !ocupado) { Text("Baixar IA") }
                        OutlinedButton(onClick = { escolherModelo.launch(arrayOf("*/*")) }, enabled = !ocupado) {
                            Text("Tenho o .gguf")
                        }
                    }
                }
            }
        }
    }

    OutlinedTextField(
        value = mensagem, onValueChange = { mensagem = it },
        label = { Text("Cole a mensagem aqui") },
        minLines = 4, modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = {
            escopo.launch {
                ocupado = true; aviso = "Lendo a mensagem…"
                aviso = try {
                    orc = Extrator.extrair(ctx, mensagem)
                    if (orc.cliente.isBlank() && orc.descricao.isBlank())
                        "A IA não preencheu. Resposta dela: " + Extrator.ultimaResposta.take(400).ifBlank { "(vazia)" }
                    else "Confira os campos antes de gerar."
                }
                catch (e: Exception) { "Erro: ${e.message}" }
                ocupado = false
            }
        },
        enabled = !ocupado && mensagem.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Preencher campos") }

    if (ocupado) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    if (aviso.isNotBlank()) Text(aviso, style = MaterialTheme.typography.bodySmall)

    Campo("Cliente", orc.cliente) { orc = orc.copy(cliente = it) }
    Campo("CNPJ / CPF", orc.documento) { orc = orc.copy(documento = it) }
    Campo("Endereço", orc.endereco) { orc = orc.copy(endereco = it) }
    Campo("Contato (telefone ou email)", orc.contato) { orc = orc.copy(contato = it) }
    Campo("Descrição do serviço", orc.descricao, linhas = 3) { orc = orc.copy(descricao = it) }
    Campo("Itens (um por linha)", orc.itens, linhas = 3) { orc = orc.copy(itens = it) }
    Campo("Valor final", orc.valor) { orc = orc.copy(valor = it) }
    Campo("Observações", orc.observacoes, linhas = 2) { orc = orc.copy(observacoes = it) }

    Button(
        onClick = {
            escopo.launch {
                try {
                    val final = orc.copy(valor = Dinheiro.formatarTexto(orc.valor))
                    orc = final
                    val pdf = withContext(Dispatchers.IO) { GeradorPdf.gerar(ctx, final, Ajustes.carregar(ctx)) }
                    compartilhar(ctx, pdf)
                } catch (e: Exception) { aviso = "Erro ao gerar PDF: ${e.message}" }
            }
        },
        enabled = !ocupado && orc.cliente.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Gerar PDF e enviar") }

    TextButton(onClick = { mensagem = ""; orc = Orcamento(); aviso = "" }) { Text("Limpar tudo") }
}

@Composable
private fun TelaAjustes(aoSair: () -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    var e by remember { mutableStateOf(Ajustes.carregar(ctx)) }
    var aviso by remember { mutableStateOf("") }
    var temLogo by remember { mutableStateOf(Ajustes.arquivoLogo(ctx).exists()) }
    var temAssinatura by remember { mutableStateOf(Ajustes.arquivoAssinatura(ctx).exists()) }

    fun importar(uri: android.net.Uri?, destino: File, pronto: () -> Unit) {
        if (uri == null) return
        escopo.launch {
            aviso = try {
                withContext(Dispatchers.IO) { Ajustes.importarImagem(ctx, uri, destino) }
                pronto(); "Imagem salva."
            } catch (ex: Exception) { "Erro: ${ex.message}" }
        }
    }
    val pegarLogo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        importar(it, Ajustes.arquivoLogo(ctx)) { temLogo = true }
    }
    val pegarAssinatura = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        importar(it, Ajustes.arquivoAssinatura(ctx)) { temAssinatura = true }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Dados da empresa", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = aoSair) { Text("Voltar") }
    }
    Campo("Sigla (usada se não tiver logo)", e.sigla) { e = e.copy(sigla = it) }
    Campo("Subtítulo da sigla", e.subtitulo) { e = e.copy(subtitulo = it) }
    Campo("CNPJ", e.cnpj) { e = e.copy(cnpj = it) }
    Campo("WhatsApp", e.telefone) { e = e.copy(telefone = it) }
    Campo("Endereço", e.endereco) { e = e.copy(endereco = it) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pegarLogo.launch("image/*") }) { Text(if (temLogo) "Trocar logo" else "Escolher logo") }
        if (temLogo) TextButton(onClick = { Ajustes.arquivoLogo(ctx).delete(); temLogo = false }) { Text("Usar sigla") }
    }
    OutlinedButton(onClick = { pegarAssinatura.launch("image/*") }) {
        Text(if (temAssinatura) "Trocar assinatura" else "Escolher foto da assinatura")
    }
    if (Extrator.modeloDisponivel(ctx)) {
        TextButton(onClick = {
            escopo.launch { Extrator.esquecerModelo(ctx); aviso = "Modelo removido. Selecione o novo na tela principal." }
        }) { Text("Trocar modelo da IA") }
    }
    if (aviso.isNotBlank()) Text(aviso, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.padding(4.dp))
    Button(onClick = { Ajustes.salvar(ctx, e); aoSair() }, modifier = Modifier.fillMaxWidth()) { Text("Salvar") }
}

@Composable
private fun Campo(rotulo: String, valor: String, linhas: Int = 1, aoMudar: (String) -> Unit) {
    OutlinedTextField(
        value = valor, onValueChange = aoMudar,
        label = { Text(rotulo) },
        singleLine = linhas == 1,
        minLines = linhas,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Download do modelo fora da tela, para não parar ao abrir os Ajustes. */
object Baixador {
    var progresso by mutableStateOf<Float?>(null)   // null = não está baixando
        private set
    var erro by mutableStateOf("")
        private set
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun iniciar(ctx: Context) {
        if (progresso != null) return
        val app = ctx.applicationContext
        progresso = 0f; erro = ""
        escopo.launch {
            try { Extrator.baixarModelo(app) { progresso = it } }
            catch (e: Exception) { erro = "Não deu para baixar: ${e.message}. Toque em Baixar IA para continuar de onde parou." }
            finally { progresso = null }
        }
    }
}

private fun compartilhar(ctx: Context, pdf: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.arquivos", pdf)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(envio, "Enviar orçamento"))
}
