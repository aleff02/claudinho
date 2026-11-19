# Laudo de Avaria — Frota

App web (celular) para os frotas registrarem avarias por placa e justificarem o reparo.
Tudo cai numa planilha Google única, separada por fornecedor (Pneus, Vidros, Funilaria, etc.).

- **Formulário (frotas):** `https://aleff02.github.io/claudinho/laudo/`
- **Painel (gestão):** `https://aleff02.github.io/claudinho/laudo/#painel`

## Ativar o site (uma vez)
GitHub > repositório **claudinho** > Settings > Pages > Source: *Deploy from a branch* > Branch `main`, pasta `/docs` > Save.

## Envio direto para o GitHub (recomendado)
Os dados vão para um repositório **privado** separado (`aleff02/laudos-avaria`): cada laudo vira `laudos/AAAA-MM/PROTOCOLO.json` e as fotos ficam em `fotos/AAAA-MM/`.

1. Crie o repositório privado **laudos-avaria** (com README, para já ter a branch `main`).
2. Crie um token *fine-grained* em GitHub > Settings > Developer settings > Personal access tokens > Fine-grained:
   - Repository access: **Only select repositories** > `laudos-avaria` (somente ele)
   - Permissions > Repository > **Contents: Read and write** (nada mais)
3. Mande aos frotas o link `https://aleff02.github.io/claudinho/laudo/#gh=SEU_TOKEN`. Ao abrir, o token fica salvo no celular e some da barra de endereço.
4. Painel: `https://aleff02.github.io/claudinho/laudo/#painel&gh=SEU_TOKEN`.

O token não fica no código (o repositório do site é público). Se o link vazar, revogue o token e gere outro.

## Alternativa: base no Google Sheets
1. Crie uma planilha Google nova (ex.: "Base Laudos de Avaria").
2. Extensões > Apps Script > apague o conteúdo e cole `apps-script/Code.gs`.
3. Troque `PAINEL_KEY` por uma senha sua.
4. Implantar > Nova implantação > tipo **App da Web** > Executar como **Eu** > Acesso **Qualquer pessoa** > Implantar > autorize.
5. Copie a URL que termina em `/exec` e cole em `CONFIG.API_URL` no `index.html` (linha "CONFIGURAÇÃO").

Sem a URL configurada o app funciona em **modo local**: salva no aparelho, exporta Excel e manda resumo por WhatsApp.

Fotos vão para a pasta do Drive "Laudos de Avaria - Fotos" e o link fica na planilha.
