// Pruebas automáticas de la API: recorren el flujo completo del prototipo (profesor y estudiante).
// Ejecutar: npm test
'use strict';
process.removeAllListeners('warning');
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { abrir } = require('../src/db');
const { construirApp } = require('../src/app');

let servidor; let base; let carpeta;
const t = {}; // estado compartido entre pruebas

async function api(metodo, ruta, { token, json, form } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  let body;
  if (json !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
  if (form) body = form;
  const r = await fetch(base + ruta, { method: metodo, headers, body });
  const tipo = r.headers.get('content-type') || '';
  const datos = tipo.includes('application/json') ? await r.json() : Buffer.from(await r.arrayBuffer());
  return { status: r.status, datos, headers: r.headers };
}

function formArchivo(campos, nombreCampo, nombreArchivo, contenido, mime) {
  const f = new FormData();
  for (const [k, val] of Object.entries(campos)) f.append(k, String(val));
  if (nombreCampo) f.append(nombreCampo, new Blob([contenido], { type: mime }), nombreArchivo);
  return f;
}

before(async () => {
  carpeta = fs.mkdtempSync(path.join(os.tmpdir(), 'biblioteca-test-'));
  const db = abrir(':memory:');
  const app = construirApp({ db, secreto: 'secreto-de-prueba', carpetaArchivos: carpeta });
  await new Promise((ok) => { servidor = app.escuchar(0, '127.0.0.1', ok); });
  base = `http://127.0.0.1:${servidor.address().port}`;
});

after(() => {
  servidor.close();
  fs.rmSync(carpeta, { recursive: true, force: true });
});

test('salud y materias', async () => {
  assert.equal((await api('GET', '/api/salud')).status, 200);
  const m = await api('GET', '/api/materias');
  assert.equal(m.status, 200);
  assert.deepEqual(m.datos.map((x) => x.nombre), ['Matemáticas', 'Inglés', 'Ciencias', 'Sociales', 'Lenguaje', 'Arte']);
  t.mate = m.datos[0].id; t.sociales = m.datos[3].id;
});

test('registro y login (crear cuenta / ya tengo cuenta)', async () => {
  const p = await api('POST', '/api/auth/registro', { json: { rol: 'profesor', nombre: 'Profe Marta', correo: 'profe@correo.com', clave: 'secreta1' } });
  assert.equal(p.status, 201);
  t.profe = p.datos.token;

  const otro = await api('POST', '/api/auth/registro', { json: { rol: 'profesor', nombre: 'Otro', correo: 'otro@correo.com', clave: 'secreta1' } });
  t.otroProfe = otro.datos.token;

  for (const [nombre, correo] of [['Ana Pérez', 'ana@correo.com'], ['Luis Gómez', 'luis@correo.com'], ['Sofía Díaz', 'sofia@correo.com'], ['Pedro Ruiz', 'pedro@correo.com']]) {
    const e = await api('POST', '/api/auth/registro', { json: { rol: 'estudiante', nombre, correo, clave: 'clave123', grado: 3, grupo: 'a' } });
    assert.equal(e.status, 201);
    assert.equal(e.datos.usuario.grupo, 'A');
    t[correo.split('@')[0]] = e.datos.token;
  }
  const fuera = await api('POST', '/api/auth/registro', { json: { rol: 'estudiante', nombre: 'Otro grupo', correo: 'b@correo.com', clave: 'clave123', grado: 3, grupo: 'B' } });
  t.grupoB = fuera.datos.token;

  assert.equal((await api('POST', '/api/auth/registro', { json: { rol: 'profesor', nombre: 'X', correo: 'profe@correo.com', clave: 'secreta1' } })).status, 409);
  assert.equal((await api('POST', '/api/auth/registro', { json: { rol: 'estudiante', nombre: 'X', correo: 'x@correo.com', clave: 'secreta1' } })).status, 400, 'estudiante sin grado');
  assert.equal((await api('POST', '/api/auth/registro', { json: { rol: 'profesor', nombre: 'X', correo: 'no-es-correo', clave: 'secreta1' } })).status, 400);
  assert.equal((await api('POST', '/api/auth/registro', { json: { rol: 'profesor', nombre: 'X', correo: 'y@c.com', clave: '123' } })).status, 400);

  const login = await api('POST', '/api/auth/login', { json: { correo: 'PROFE@correo.com', clave: 'secreta1', rol: 'profesor' } });
  assert.equal(login.status, 200);
  assert.equal(login.datos.usuario.rol, 'profesor');
  assert.equal(login.datos.usuario.clave_hash, undefined);
  assert.equal((await api('POST', '/api/auth/login', { json: { correo: 'profe@correo.com', clave: 'mala' } })).status, 401);
  assert.equal((await api('POST', '/api/auth/login', { json: { correo: 'ana@correo.com', clave: 'clave123', rol: 'profesor' } })).status, 403, 'rol equivocado');

  const yo = await api('GET', '/api/auth/yo', { token: t.ana });
  assert.equal(yo.datos.nombre, 'Ana Pérez');
  assert.equal((await api('GET', '/api/auth/yo')).status, 401);
  assert.equal((await api('GET', '/api/auth/yo', { token: 'a.b.c' })).status, 401);
});

test('profesor sube material (pantalla 2)', async () => {
  const pdf = Buffer.from('%PDF-1.4 tablas del 2');
  const r = await api('POST', '/api/materiales', { token: t.profe, form: formArchivo({ titulo: 'Las tablas del 2', tipo: 'pdf', materia_id: t.mate, grado: 3, grupo: 'A' }, 'archivo', 'tablas_del_2.pdf', pdf, 'application/pdf') });
  assert.equal(r.status, 201, JSON.stringify(r.datos));
  assert.equal(r.datos.version, 1);
  assert.equal(r.datos.tamano, pdf.length);
  t.mat1 = r.datos.id;

  const video = await api('POST', '/api/materiales', { token: t.profe, form: formArchivo({ titulo: 'Video: multiplicar', tipo: 'video', materia_id: t.mate, grado: 3, grupo: 'A' }, 'archivo', 'multiplicar.mp4', Buffer.alloc(2048, 1), 'video/mp4') });
  t.mat2 = video.datos.id;

  // 9 materiales más para probar el límite de 10 descargas
  t.extra = [];
  for (let i = 0; i < 9; i++) {
    const x = await api('POST', '/api/materiales', { token: t.profe, form: formArchivo({ titulo: `Taller ${i + 1}`, tipo: 'taller', materia_id: t.mate, grado: 3, grupo: 'A' }, 'archivo', `taller${i}.pdf`, Buffer.from('taller'), 'application/pdf') });
    t.extra.push(x.datos.id);
  }

  assert.equal((await api('POST', '/api/materiales', { token: t.profe, form: formArchivo({ titulo: 'Sin archivo', tipo: 'pdf', materia_id: t.mate, grado: 3, grupo: 'A' }) })).status, 400);
  assert.equal((await api('POST', '/api/materiales', { token: t.profe, form: formArchivo({ titulo: 'Tipo malo', tipo: 'mp3', materia_id: t.mate, grado: 3, grupo: 'A' }, 'archivo', 'a.mp3', Buffer.from('x'), 'audio/mpeg') })).status, 400);
  assert.equal((await api('POST', '/api/materiales', { token: t.ana, form: formArchivo({ titulo: 'X', tipo: 'pdf', materia_id: t.mate, grado: 3, grupo: 'A' }, 'archivo', 'a.pdf', Buffer.from('x'), 'application/pdf') })).status, 403, 'estudiante no publica');

  const lista = await api('GET', `/api/materiales?materia_id=${t.mate}&grado=3&grupo=A`, { token: t.profe });
  assert.equal(lista.datos.length, 11);
  assert.equal(lista.datos[0].materia, 'Matemáticas');
});

test('profesor crea una evaluación (pantalla 3)', async () => {
  const r = await api('POST', '/api/pruebas', { token: t.profe, json: {
    titulo: 'La Revolución Francesa', materia_id: t.sociales, grado: 3, grupo: 'A',
    preguntas: [
      { enunciado: '¿En qué año fue la Revolución Francesa?', opciones: ['1789', '1810', '1492'], correcta: 0 },
      { enunciado: '¿En qué país ocurrió?', opciones: ['España', 'Francia'], correcta: 1 },
      { enunciado: '¿Qué cayó el 14 de julio?', opciones: ['La Bastilla', 'El muro', 'La torre'], correcta: 0 },
    ] } });
  assert.equal(r.status, 201, JSON.stringify(r.datos));
  assert.equal(r.datos.preguntas.length, 3);
  assert.deepEqual(r.datos.preguntas[0].opciones, ['1789', '1810', '1492']);
  t.prueba = r.datos.id;

  assert.equal((await api('POST', '/api/pruebas', { token: t.profe, json: { titulo: 'Mala', materia_id: t.sociales, grado: 3, grupo: 'A', preguntas: [{ enunciado: '¿?', opciones: ['a'], correcta: 0 }] } })).status, 400);
  assert.equal((await api('POST', '/api/pruebas', { token: t.profe, json: { titulo: 'Mala', materia_id: t.sociales, grado: 3, grupo: 'A', preguntas: [{ enunciado: '¿?', opciones: ['a', 'b'], correcta: 5 }] } })).status, 400);
  assert.equal((await api('GET', `/api/pruebas/${t.prueba}`, { token: t.otroProfe })).status, 403);
  const lista = await api('GET', '/api/pruebas', { token: t.profe });
  assert.equal(lista.datos[0].num_preguntas, 3);
});

test('profesor programa un parcial (pantalla 4)', async () => {
  const r = await api('POST', '/api/parciales', { token: t.profe, json: {
    titulo: 'Parcial de Sociales', fecha: '2026-10-15', aviso: 'Repasen las clases 1 a 3', materia_id: t.sociales, grado: 3, grupo: 'A', material_ids: [t.mat1, t.mat2] } });
  assert.equal(r.status, 201, JSON.stringify(r.datos));
  assert.equal(r.datos.materiales.length, 2);
  t.parcial = r.datos.id;
  assert.equal((await api('POST', '/api/parciales', { token: t.profe, json: { titulo: 'X', fecha: '15/10/2026', materia_id: t.sociales, grado: 3, grupo: 'A' } })).status, 400);
  assert.equal((await api('POST', '/api/parciales', { token: t.otroProfe, json: { titulo: 'X', fecha: '2026-10-15', materia_id: t.sociales, grado: 3, grupo: 'A', material_ids: [t.mat1] } })).status, 400);
});

test('profesor crea un juego con su contenido (pantalla 8)', async () => {
  const png = Buffer.from('89504e470d0a1a0a', 'hex');
  const img = await api('POST', '/api/juegos/imagenes', { token: t.profe, form: formArchivo({}, 'imagen', 'vaca.png', png, 'image/png') });
  assert.equal(img.status, 201);
  const ver = await api('GET', img.datos.url);
  assert.equal(ver.status, 200);
  assert.equal(ver.headers.get('content-type'), 'image/png');

  const r = await api('POST', '/api/juegos', { token: t.profe, json: {
    tipo: 'unir', titulo: 'Animales de la granja', materia_id: t.mate, grado: 3, grupo: 'A',
    parejas: [{ palabra: 'Vaca', archivo: img.datos.archivo }, { palabra: 'Gallina', imagen: '🐔' }] } });
  assert.equal(r.status, 201, JSON.stringify(r.datos));
  assert.equal(r.datos.de_profesor, true);
  t.juego = r.datos.id;
  assert.equal((await api('POST', '/api/juegos', { token: t.profe, json: { tipo: 'unir', titulo: 'X', materia_id: t.mate, grado: 3, grupo: 'A', parejas: [{ palabra: 'Sola' }] } })).status, 400);
  assert.equal((await api('POST', '/api/juegos', { token: t.profe, json: { tipo: 'damas', titulo: 'X', materia_id: t.mate, grado: 3, grupo: 'A', parejas: [] } })).status, 400);
});

test('resumen del inicio del profesor (pantalla 1)', async () => {
  const r = await api('GET', `/api/profesor/resumen?materia_id=${t.mate}&grado=3&grupo=A`, { token: t.profe });
  assert.equal(r.status, 200);
  assert.equal(r.datos.materiales, 11);
  assert.equal(r.datos.juegos, 1);
  assert.equal(r.datos.estudiantes, 4);
  assert.ok(r.datos.ultimos.length > 0);
  assert.equal((await api('GET', '/api/profesor/resumen?materia_id=1&grado=3', { token: t.profe })).status, 400);
});

test('estudiante sincroniza su grupo (pantalla 8 estudiante)', async () => {
  const s = await api('GET', '/api/estudiante/sync', { token: t.ana });
  assert.equal(s.status, 200);
  assert.equal(s.datos.materiales.length, 11);
  assert.equal(s.datos.pruebas.length, 1);
  assert.equal(s.datos.pruebas[0].mi_resultado, null);
  assert.equal(s.datos.parciales[0].aviso, 'Repasen las clases 1 a 3');
  assert.ok(s.datos.juegos.some((j) => j.titulo === 'Animales de la granja'));
  assert.ok(s.datos.juegos.some((j) => !j.de_profesor), 'incluye los juegos de la app');
  assert.ok(s.datos.datos.length >= 6);
  assert.deepEqual(s.datos.descargas, { activas: 0, maximo: 10 });

  const b = await api('GET', '/api/estudiante/sync', { token: t.grupoB });
  assert.equal(b.datos.materiales.length, 0, 'el grupo B no ve el material del grupo A');
  assert.equal((await api('GET', '/api/estudiante/sync', { token: t.profe })).status, 403);
});

test('descargas: una vez, máximo 10 activas (pantallas 2 y 8 estudiante)', async () => {
  const d = await api('GET', `/api/materiales/${t.mat1}/descargar`, { token: t.ana });
  assert.equal(d.status, 200);
  assert.equal(d.datos.toString(), '%PDF-1.4 tablas del 2');
  assert.equal(d.headers.get('x-version'), '1');
  // volver a bajarlo no gasta cupo
  await api('GET', `/api/materiales/${t.mat1}/descargar`, { token: t.ana });
  await api('GET', `/api/materiales/${t.mat2}/descargar`, { token: t.ana });
  for (const id of t.extra.slice(0, 8)) assert.equal((await api('GET', `/api/materiales/${id}/descargar`, { token: t.ana })).status, 200);
  const once = await api('GET', `/api/materiales/${t.extra[8]}/descargar`, { token: t.ana });
  assert.equal(once.status, 409, 'la descarga 11 se rechaza');
  assert.match(once.datos.error, /máximo de 10/);

  const q = await api('POST', `/api/descargas/${t.extra[0]}/quitar`, { token: t.ana });
  assert.equal(q.datos.activas, 9);
  assert.equal((await api('GET', `/api/materiales/${t.extra[8]}/descargar`, { token: t.ana })).status, 200, 'con cupo libre sí descarga');
  assert.equal((await api('POST', `/api/descargas/${t.extra[0]}/quitar`, { token: t.ana })).status, 404);

  assert.equal((await api('GET', `/api/materiales/${t.mat1}/descargar`, { token: t.grupoB })).status, 403);
  assert.equal((await api('GET', `/api/materiales/${t.mat1}/descargar`, { token: t.profe })).status, 200, 'el profesor dueño puede verlo');
  assert.equal((await api('GET', `/api/materiales/${t.mat1}/descargar`, { token: t.otroProfe })).status, 403);

  await api('GET', `/api/materiales/${t.mat2}/descargar`, { token: t.luis });
  await api('GET', `/api/materiales/${t.mat1}/descargar`, { token: t.sofia });
});

test('estudiante responde la prueba y envía sin internet (pantallas 4 y 8)', async () => {
  // Ana la responde en línea: 2 de 3
  const r = await api('POST', `/api/pruebas/${t.prueba}/responder`, { token: t.ana, json: { respuestas: [0, 1, 2] } });
  assert.equal(r.status, 201);
  assert.equal(r.datos.correctas, 2);
  // Un segundo intento no cambia la nota
  const otra = await api('POST', `/api/pruebas/${t.prueba}/responder`, { token: t.ana, json: { respuestas: [0, 1, 0] } });
  assert.equal(otra.status, 200);
  assert.equal(otra.datos.correctas, 2);
  assert.equal((await api('POST', `/api/pruebas/${t.prueba}/responder`, { token: t.ana, json: { respuestas: [0] } })).status, 400);
  assert.equal((await api('POST', `/api/pruebas/${t.prueba}/responder`, { token: t.grupoB, json: { respuestas: [0, 1, 0] } })).status, 403);

  // Luis la hizo sin internet y la envía al actualizar, junto con una descarga que quitó
  const e = await api('POST', '/api/estudiante/enviar', { token: t.luis, json: {
    resultados: [{ prueba_id: t.prueba, respuestas: [0, 1, 0], presentada_en: '2026-10-12T15:00:00Z' }, { prueba_id: 9999, respuestas: [] }],
    quitadas: [t.mat2] } });
  assert.equal(e.status, 200);
  assert.equal(e.datos.resultados[0].correctas, 3);
  assert.equal(e.datos.resultados[0].presentada_en, '2026-10-12T15:00:00.000Z');
  assert.equal(e.datos.errores.length, 1);
  assert.equal(e.datos.descargas_activas, 0);

  const s = await api('GET', '/api/estudiante/sync', { token: t.ana });
  assert.deepEqual({ ...s.datos.pruebas[0].mi_resultado, presentada_en: undefined }, { correctas: 2, total: 3, presentada_en: undefined });
});

test('resultados para el profesor (pantalla 7)', async () => {
  const r = await api('GET', `/api/pruebas/${t.prueba}/resultados`, { token: t.profe });
  assert.equal(r.status, 200);
  assert.equal(r.datos.estudiantes_grupo, 4);
  assert.equal(r.datos.presentaron, 2);
  assert.equal(r.datos.promedio, 2.5);
  const pedro = r.datos.por_estudiante.find((x) => x.nombre === 'Pedro Ruiz');
  assert.equal(pedro.falta, true);
});

test('quién descargó qué (pantalla 6)', async () => {
  const r = await api('GET', '/api/profesor/descargas?grado=3&grupo=A', { token: t.profe });
  assert.equal(r.status, 200);
  assert.equal(r.datos.estudiantes, 4);
  assert.equal(r.datos.ya_descargaron, 3);
  assert.ok(r.datos.filas.some((f) => f.nombre === 'Pedro Ruiz' && f.estado === 'pendiente'));
  assert.ok(r.datos.filas.some((f) => f.nombre === 'Luis Gómez' && f.estado === 'quitada'));
  assert.ok(r.datos.filas.some((f) => f.nombre === 'Sofía Díaz' && f.estado === 'activa'));
});

test('actualizar, ocultar y borrar material (pantalla 5)', async () => {
  const nuevo = await api('PUT', `/api/materiales/${t.mat1}/archivo`, { token: t.profe, form: formArchivo({}, 'archivo', 'tablas_v2.pdf', Buffer.from('%PDF-1.4 version 2'), 'application/pdf') });
  assert.equal(nuevo.status, 200);
  assert.equal(nuevo.datos.version, 2);
  const d = await api('GET', `/api/materiales/${t.mat1}/descargar`, { token: t.ana });
  assert.equal(d.headers.get('x-version'), '2');
  assert.equal(d.datos.toString(), '%PDF-1.4 version 2');

  const oculto = await api('PATCH', `/api/materiales/${t.mat1}`, { token: t.profe, json: { visible: false } });
  assert.equal(oculto.datos.visible, false);
  let s = await api('GET', '/api/estudiante/sync', { token: t.ana });
  assert.ok(!s.datos.materiales.some((m) => m.id === t.mat1), 'oculto: el estudiante no lo ve');
  assert.equal((await api('GET', `/api/materiales/${t.mat1}/descargar`, { token: t.ana })).status, 403);
  await api('PATCH', `/api/materiales/${t.mat1}`, { token: t.profe, json: { visible: true } });

  assert.equal((await api('PATCH', `/api/materiales/${t.mat1}`, { token: t.otroProfe, json: { visible: false } })).status, 403);
  assert.equal((await api('DELETE', `/api/materiales/${t.extra[1]}`, { token: t.profe })).status, 200);
  assert.equal((await api('DELETE', `/api/materiales/${t.extra[1]}`, { token: t.profe })).status, 404);
  s = await api('GET', '/api/estudiante/sync', { token: t.ana });
  assert.equal(s.datos.materiales.length, 10);
  assert.equal(s.datos.descargas.activas, 8, 'borrar e ocultar libera los cupos');
});

test('datos curiosos y borrados', async () => {
  const d = await api('POST', '/api/datos', { token: t.profe, json: { materia_id: t.sociales, texto: 'Bogotá se fundó en 1538.', enlace: 'https://es.wikipedia.org/wiki/Bogot%C3%A1' } });
  assert.equal(d.status, 201);
  assert.equal((await api('POST', '/api/datos', { token: t.profe, json: { materia_id: t.sociales, texto: 'x', enlace: 'ftp://x' } })).status, 400);
  const lista = await api('GET', `/api/datos?materia_id=${t.sociales}`, { token: t.ana });
  assert.equal(lista.datos.length, 2);
  assert.equal((await api('DELETE', `/api/datos/${d.datos.id}`, { token: t.profe })).status, 200);
  assert.equal((await api('DELETE', `/api/juegos/${t.juego}`, { token: t.profe })).status, 200);
  assert.equal((await api('DELETE', `/api/parciales/${t.parcial}`, { token: t.profe })).status, 200);
  assert.equal((await api('DELETE', `/api/pruebas/${t.prueba}`, { token: t.profe })).status, 200);
  assert.equal((await api('GET', '/api/no-existe')).status, 404);
  const malo = await fetch(base + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{malo' });
  assert.equal(malo.status, 400);
});
