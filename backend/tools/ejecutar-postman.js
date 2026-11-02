// Ejecuta una colección de Postman v2.1 desde la terminal (sin instalar newman).
// Implementa lo que usa nuestra colección: variables, scripts pre-request y pm.test / pm.expect.
// Uso: node tools/ejecutar-postman.js postman/Biblioteca.postman_collection.json [baseUrl]
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const rutaColeccion = path.resolve(process.argv[2] || 'postman/Biblioteca.postman_collection.json');
const coleccion = JSON.parse(fs.readFileSync(rutaColeccion, 'utf8'));
const carpetaBase = path.dirname(rutaColeccion);
const variables = Object.fromEntries((coleccion.variable || []).map((v) => [v.key, String(v.value ?? '')]));
if (process.argv[3]) variables.baseUrl = process.argv[3];

const reemplazar = (s) => String(s).replace(/\{\{(\w+)\}\}/g, (_, k) => (k in variables ? variables[k] : `{{${k}}}`));

function expect(valor) {
  const neg = { activo: false };
  const api = {
    get to() { return api; }, get be() { return api; }, get have() { return api; },
    get not() { neg.activo = !neg.activo; return api; },
    eql(esperado) { neg.activo ? assert.notDeepStrictEqual(valor, esperado) : assert.deepStrictEqual(valor, esperado); return api; },
    equal(esperado) { neg.activo ? assert.notStrictEqual(valor, esperado) : assert.strictEqual(valor, esperado); return api; },
    above(n) { assert.ok(valor > n, `se esperaba ${valor} > ${n}`); return api; },
    include(x) { const ok = valor.includes(x); assert.ok(neg.activo ? !ok : ok, `${JSON.stringify(valor)} ${neg.activo ? 'no ' : ''}debe incluir ${JSON.stringify(x)}`); return api; },
    property(p) { const ok = valor != null && Object.prototype.hasOwnProperty.call(valor, p); assert.ok(neg.activo ? !ok : ok, `propiedad "${p}"`); return api; },
    lengthOf(n) { assert.strictEqual(valor.length, n); return api; },
    an(tipo) { assert.ok(tipo === 'array' ? Array.isArray(valor) : typeof valor === tipo, `se esperaba ${tipo}`); return api; },
    get true() { assert.strictEqual(valor, true); return api; },
  };
  return api;
}

async function ejecutar() {
  let total = 0; let fallas = 0;
  const recorrer = async (items, nivel = 0) => {
    for (const item of items) {
      if (item.item) { console.log(`\n${'  '.repeat(nivel)}📁 ${item.name}`); await recorrer(item.item, nivel + 1); continue; }
      const resultados = [];
      let respuesta = null; let cuerpoTexto = ''; let cuerpoJson;
      const pm = {
        collectionVariables: { get: (k) => variables[k], set: (k, v) => { variables[k] = String(v); } },
        environment: { get: (k) => variables[k], set: (k, v) => { variables[k] = String(v); } },
        variables: { get: (k) => variables[k], replaceIn: reemplazar },
        test: (nombre, fn) => {
          try { fn(); resultados.push([true, nombre]); } catch (e) { resultados.push([false, nombre, e.message.split('\n')[0]]); }
        },
        expect,
        response: {
          get code() { return respuesta.status; },
          json: () => { if (cuerpoJson === undefined) cuerpoJson = JSON.parse(cuerpoTexto); return cuerpoJson; },
          text: () => cuerpoTexto,
          headers: { get: (h) => respuesta.headers.get(h) },
          to: { have: { status: (n) => assert.strictEqual(respuesta.status, n, `status ${respuesta.status}, se esperaba ${n}: ${cuerpoTexto.slice(0, 120)}`) } },
        },
      };
      const correrScript = (tipo) => {
        const ev = (item.event || []).find((e) => e.listen === tipo);
        if (ev) new Function('pm', ev.script.exec.join('\n'))(pm);
      };

      correrScript('prerequest');
      const r = item.request;
      const headers = {};
      for (const h of r.header || []) headers[h.key] = reemplazar(h.value);
      let body;
      if (r.body?.mode === 'raw') { body = reemplazar(r.body.raw); headers['Content-Type'] = 'application/json'; }
      if (r.body?.mode === 'formdata') {
        body = new FormData();
        for (const f of r.body.formdata) {
          if (f.type === 'file') {
            const ruta = path.resolve(carpetaBase, f.src);
            body.append(f.key, new Blob([fs.readFileSync(ruta)]), path.basename(ruta));
          } else body.append(f.key, reemplazar(f.value));
        }
      }
      const url = reemplazar(r.url.raw ?? r.url);
      const inicio = Date.now();
      respuesta = await fetch(url, { method: r.method, headers, body });
      cuerpoTexto = Buffer.from(await respuesta.arrayBuffer()).toString('utf8');
      correrScript('test');

      const ms = Date.now() - inicio;
      console.log(`${'  '.repeat(nivel)}→ ${item.name}  [${r.method} ${new URL(url).pathname} → ${respuesta.status}, ${ms} ms]`);
      for (const [ok, nombre, err] of resultados) {
        total++; if (!ok) fallas++;
        console.log(`${'  '.repeat(nivel + 1)}${ok ? '✔' : '✘'} ${nombre}${err ? `  ← ${err}` : ''}`);
      }
    }
  };
  await recorrer(coleccion.item);
  console.log(`\n${total - fallas} de ${total} pruebas pasaron${fallas ? ` (${fallas} fallaron)` : ' ✅'}`);
  process.exit(fallas ? 1 : 0);
}

ejecutar().catch((e) => { console.error('No se pudo ejecutar la colección:', e.message); process.exit(1); });
