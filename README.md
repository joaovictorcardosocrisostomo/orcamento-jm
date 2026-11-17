# Orçamento JM — app Android

Cola (ou compartilha do WhatsApp) uma mensagem com os detalhes do serviço → o app preenche os campos com o NuExtract rodando **offline no celular** → você confere → gera o PDF no modelo JM e envia.

## Como rodar

1. Instale o **Android Studio** e abra esta pasta (`File > Open`). Ele baixa o Gradle e as bibliotecas sozinho no primeiro *sync*. Se pedir para instalar o SDK 36, aceite.
2. No celular, ative a **Depuração USB** (Configurações > Sobre o telefone > toque 7x em "Número da versão" > Opções do desenvolvedor). Ligue no PC e clique em ▶ Run.
   - Ou gere o APK em `Build > Generate App Bundles or APKs > Build APK(s)` e instale no celular.
3. Baixe o modelo **no celular** (≈400 MB):
   https://huggingface.co/tensorblock/NuExtract-1.5-tiny-GGUF → arquivo `NuExtract-1.5-tiny-Q4_K_M.gguf`
4. Abra o app → **Selecionar modelo (.gguf)** → escolha o arquivo baixado. Ele é copiado uma vez só.
5. Em **Ajustes**: confira CNPJ, WhatsApp e endereço, escolha a logo (opcional — sem ela o app desenha "JM" em vermelho) e uma foto da assinatura (de preferência em papel branco, recortada).

## Como usar

- No WhatsApp: segure a mensagem → Compartilhar → **Orçamento JM**. Ou copie e cole no app.
- **Preencher campos** → confira/corrija → **Gerar PDF e enviar**.
- Itens: um por linha. Com 1 item, o valor sai na mesma linha; com vários, numa linha abaixo (igual aos modelos).

## Como funciona

| Arquivo | O que faz |
|---|---|
| `Extrator.kt` | Monta o prompt do NuExtract com o molde JSON, roda o modelo e completa com regex (valor `R$`, CNPJ/CPF, telefone). O valor da regex tem prioridade sobre o da IA. |
| `GeradorPdf.kt` | Desenha a folha A4 com `PdfDocument` (cabeçalho, caixa "Valor Final", cliente, descrição, tabela de itens, observações, assinatura). |
| `Ajustes.kt` | Dados da empresa, logo e assinatura. |
| `MainActivity.kt` | As duas telas (principal e ajustes) e o compartilhamento. |

Para mudar o que a IA extrai, edite o `MOLDE` em `Extrator.kt`.

## Se der erro no build

As versões (AGP 9.4.0, Kotlin 2.4.21, Gradle 9.8.2, `com.llamatik:core:2.4.0`) são compatíveis com a biblioteca Llamatik. Se o Android Studio sugerir atualizar algo no *sync*, pode aceitar.
