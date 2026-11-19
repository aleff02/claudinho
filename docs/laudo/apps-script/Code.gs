/**
 * LAUDO DE AVARIA — backend gratuito no Google Sheets
 * Cole este arquivo em: Planilha > Extensões > Apps Script.
 * Depois: Implantar > Nova implantação > App da Web
 *   - Executar como: Eu
 *   - Quem pode acessar: Qualquer pessoa
 * Copie a URL /exec e cole em CONFIG.API_URL no index.html.
 */

const PAINEL_KEY  = 'troque-esta-chave';      // senha do painel de gestão (#painel)
const ABA         = 'Avarias';
const PASTA_FOTOS = 'Laudos de Avaria - Fotos';

const CABECALHO = ['ID','Protocolo','Data/Hora','Placa','Loja','Regional','KM','Fornecedor','Categoria','Peça/Local',
  'Tipo de dano','Gravidade','Motivo do reparo','Descrição','Fotos','Responsável','Contato','Obs. geral','Status','Atualizado em'];

function aba_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(ABA);
  if (!sh) {
    sh = ss.insertSheet(ABA);
    sh.appendRow(CABECALHO);
    sh.getRange(1, 1, 1, CABECALHO.length).setFontWeight('bold').setBackground('#0c1322').setFontColor('#22e4ff');
    sh.setFrozenRows(1);
    sh.getRange('C:C').setNumberFormat('dd/MM/yyyy HH:mm');
  }
  return sh;
}

function pasta_() {
  const it = DriveApp.getFoldersByName(PASTA_FOTOS);
  return it.hasNext() ? it.next() : DriveApp.createFolder(PASTA_FOTOS);
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const p = JSON.parse(e.postData.contents);
    if (p.action === 'status') return json_(mudarStatus_(p));
    return json_(salvarLaudo_(p));
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function salvarLaudo_(p) {
  const sh = aba_();
  // evita duplicar se o mesmo laudo for reenviado
  const protos = sh.getLastRow() > 1 ? sh.getRange(2, 2, sh.getLastRow() - 1, 1).getValues().flat() : [];
  if (protos.indexOf(p.protocolo) >= 0) return { ok: true, protocolo: p.protocolo, duplicado: true };

  const pasta = (p.avarias || []).some(a => (a.fotos || []).length) ? pasta_() : null;
  const agora = new Date();
  const linhas = (p.avarias || []).map((a, i) => {
    const links = (a.fotos || []).map((d, j) => {
      try {
        const m = d.match(/^data:(.+?);base64,(.*)$/);
        const blob = Utilities.newBlob(Utilities.base64Decode(m[2]), m[1], `${p.placa}_${p.protocolo}_${i + 1}-${j + 1}.jpg`);
        const f = pasta.createFile(blob);
        try { f.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW); } catch (x) {}
        return f.getUrl();
      } catch (x) { return ''; }
    }).filter(String).join('\n');
    return [`${p.protocolo}-${i + 1}`, p.protocolo, new Date(p.dataHora || agora), p.placa, p.loja, p.regional, p.km,
      a.fornecedor, a.categoria, a.peca, a.tipo, a.severidade, a.motivo, a.descricao, links,
      p.responsavel, p.contato, p.obs, 'Pendente', agora];
  });
  if (linhas.length) sh.getRange(sh.getLastRow() + 1, 1, linhas.length, CABECALHO.length).setValues(linhas);
  return { ok: true, protocolo: p.protocolo, linhas: linhas.length };
}

function mudarStatus_(p) {
  if (p.key !== PAINEL_KEY) return { ok: false, error: 'Chave inválida' };
  const sh = aba_();
  const ids = sh.getRange(2, 1, Math.max(sh.getLastRow() - 1, 1), 1).getValues().flat().map(String);
  const i = ids.indexOf(String(p.id));
  if (i < 0) return { ok: false, error: 'ID não encontrado' };
  sh.getRange(i + 2, CABECALHO.indexOf('Status') + 1).setValue(p.status);
  sh.getRange(i + 2, CABECALHO.indexOf('Atualizado em') + 1).setValue(new Date());
  return { ok: true };
}

function doGet(e) {
  const q = e.parameter || {};
  if (q.action !== 'list') return json_({ ok: true, msg: 'Laudo de Avaria API online' });
  if (q.key !== PAINEL_KEY) return json_({ ok: false, error: 'Chave inválida' });
  const v = aba_().getDataRange().getValues();
  const h = v.shift();
  const rows = v.map(r => {
    const o = {};
    h.forEach((k, i) => o[k] = r[i] instanceof Date ? r[i].toISOString() : r[i]);
    return o;
  });
  return json_({ ok: true, rows });
}
