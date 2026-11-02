// Genera postman/Biblioteca.postman_collection.json (formato Postman v2.1).
// Uso: node tools/generar-postman.js
'use strict';
const fs = require('node:fs');
const path = require('node:path');

const json = (obj) => ({ mode: 'raw', raw: JSON.stringify(obj, null, 2).replace(/"__(\w+)__"/g, '{{$1}}'), options: { raw: { language: 'json' } } });
const auth = (quien) => [{ key: 'Authorization', value: `Bearer {{token${quien}}}` }];

function req(nombre, metodo, ruta, { quien, body, form, pre, tests, descripcion } = {}) {
  const item = {
    name: nombre,
    request: {
      method: metodo,
      header: quien ? auth(quien) : [],
      url: { raw: `{{baseUrl}}${ruta}`, host: ['{{baseUrl}}'], path: ruta.split('?')[0].split('/').filter(Boolean),
        ...(ruta.includes('?') ? { query: ruta.split('?')[1].split('&').map((q) => { const [key, value] = q.split('='); return { key, value }; }) } : {}) },
      description: descripcion || '',
    },
    event: [],
  };
  if (body) item.request.body = json(body);
  if (form) item.request.body = { mode: 'formdata', formdata: form };
  if (pre) item.event.push({ listen: 'prerequest', script: { type: 'text/javascript', exec: pre.trim().split('\n') } });
  if (tests) item.event.push({ listen: 'test', script: { type: 'text/javascript', exec: tests.trim().split('\n') } });
  return item;
}

const coleccion = {
  info: {
    name: 'Biblioteca Digital API',
    description: 'Colección de pruebas de la API (etapa 3: Backend). Ejecuta las carpetas en orden con el Collection Runner: cada petición guarda en variables los ids y tokens que usa la siguiente.\n\nAntes de empezar: inicia el servidor (`node server.js`) y, para las peticiones con archivo, en Postman ve a Settings → General → Working directory y elige la carpeta `postman` del proyecto.',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
  },
  variable: [
    { key: 'baseUrl', value: 'http://localhost:3000' },
    { key: 'sufijo', value: '' },
    { key: 'tokenProfesor', value: '' },
    { key: 'tokenEstudiante', value: '' },
    { key: 'materiaMate', value: '1' },
    { key: 'materiaSociales', value: '4' },
    { key: 'materialId', value: '' },
    { key: 'pruebaId', value: '' },
    { key: 'imagenArchivo', value: '' },
  ],
  item: [
    {
      name: '1. Autenticación',
      item: [
        req('Salud del servidor', 'GET', '/api/salud', { tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
pm.test("Estado ok", () => pm.expect(pm.response.json().estado).to.eql("ok"));` }),
        req('Listar materias', 'GET', '/api/materias', { tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
const m = pm.response.json();
pm.test("Trae las 6 materias", () => pm.expect(m).to.have.lengthOf(6));
pm.collectionVariables.set("materiaMate", m.find(x => x.nombre === "Matemáticas").id);
pm.collectionVariables.set("materiaSociales", m.find(x => x.nombre === "Sociales").id);` }),
        req('Crear cuenta de profesor', 'POST', '/api/auth/registro', {
          pre: 'pm.collectionVariables.set("sufijo", Date.now());',
          body: { rol: 'profesor', nombre: 'Profe Marta', correo: 'profe{{sufijo}}@correo.com', clave: 'secreta1' },
          tests: `
pm.test("Responde 201", () => pm.response.to.have.status(201));
const r = pm.response.json();
pm.test("Devuelve token", () => pm.expect(r).to.have.property("token"));
pm.test("Rol profesor", () => pm.expect(r.usuario.rol).to.eql("profesor"));
pm.collectionVariables.set("tokenProfesor", r.token);` }),
        req('Crear cuenta de estudiante', 'POST', '/api/auth/registro', {
          body: { rol: 'estudiante', nombre: 'Ana Pérez', correo: 'ana{{sufijo}}@correo.com', clave: 'clave123', grado: 3, grupo: 'A' },
          tests: `
pm.test("Responde 201", () => pm.response.to.have.status(201));
const r = pm.response.json();
pm.test("Queda en 3° A", () => { pm.expect(r.usuario.grado).to.eql(3); pm.expect(r.usuario.grupo).to.eql("A"); });
pm.collectionVariables.set("tokenEstudiante", r.token);` }),
        req('Correo repetido (debe fallar)', 'POST', '/api/auth/registro', {
          body: { rol: 'profesor', nombre: 'Otra', correo: 'profe{{sufijo}}@correo.com', clave: 'secreta1' },
          tests: 'pm.test("Responde 409", () => pm.response.to.have.status(409));' }),
        req('Iniciar sesión (ya tengo cuenta)', 'POST', '/api/auth/login', {
          body: { correo: 'profe{{sufijo}}@correo.com', clave: 'secreta1', rol: 'profesor' },
          tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
pm.test("No expone la contraseña", () => pm.expect(pm.response.json().usuario).to.not.have.property("clave_hash"));
pm.collectionVariables.set("tokenProfesor", pm.response.json().token);` }),
        req('Contraseña incorrecta (debe fallar)', 'POST', '/api/auth/login', {
          body: { correo: 'profe{{sufijo}}@correo.com', clave: 'equivocada' },
          tests: 'pm.test("Responde 401", () => pm.response.to.have.status(401));' }),
        req('Mi perfil', 'GET', '/api/auth/yo', { quien: 'Estudiante', tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
pm.test("Es estudiante", () => pm.expect(pm.response.json().rol).to.eql("estudiante"));` }),
      ],
    },
    {
      name: '2. Profesor',
      item: [
        req('Subir material (PDF)', 'POST', '/api/materiales', {
          quien: 'Profesor',
          form: [
            { key: 'titulo', value: 'Las tablas del 2', type: 'text' },
            { key: 'tipo', value: 'pdf', type: 'text' },
            { key: 'materia_id', value: '{{materiaMate}}', type: 'text' },
            { key: 'grado', value: '3', type: 'text' },
            { key: 'grupo', value: 'A', type: 'text' },
            { key: 'archivo', type: 'file', src: 'archivos-prueba/tablas_del_2.pdf' },
          ],
          tests: `
pm.test("Responde 201", () => pm.response.to.have.status(201));
const m = pm.response.json();
pm.test("Versión 1 y visible", () => { pm.expect(m.version).to.eql(1); pm.expect(m.visible).to.eql(true); });
pm.collectionVariables.set("materialId", m.id);` }),
        req('Listar mi material', 'GET', '/api/materiales?materia_id={{materiaMate}}&grado=3&grupo=A', { quien: 'Profesor', tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
pm.test("Incluye el material subido", () => pm.expect(pm.response.json().map(m => m.id)).to.include(Number(pm.collectionVariables.get("materialId"))));` }),
        req('Crear prueba', 'POST', '/api/pruebas', {
          quien: 'Profesor',
          body: { titulo: 'La Revolución Francesa', materia_id: '__materiaSociales__', grado: 3, grupo: 'A', preguntas: [
            { enunciado: '¿En qué año fue la Revolución Francesa?', opciones: ['1789', '1810', '1492'], correcta: 0 },
            { enunciado: '¿En qué país ocurrió?', opciones: ['España', 'Francia', 'Italia'], correcta: 1 },
          ] },
          tests: `
pm.test("Responde 201", () => pm.response.to.have.status(201));
pm.test("Tiene 2 preguntas", () => pm.expect(pm.response.json().preguntas).to.have.lengthOf(2));
pm.collectionVariables.set("pruebaId", pm.response.json().id);` }),
        req('Prueba con una sola opción (debe fallar)', 'POST', '/api/pruebas', {
          quien: 'Profesor',
          body: { titulo: 'Mala', materia_id: '__materiaSociales__', grado: 3, grupo: 'A', preguntas: [{ enunciado: '¿?', opciones: ['a'], correcta: 0 }] },
          tests: 'pm.test("Responde 400", () => pm.response.to.have.status(400));' }),
        req('Programar parcial', 'POST', '/api/parciales', {
          quien: 'Profesor',
          body: { titulo: 'Parcial de Sociales', fecha: '2026-10-15', aviso: 'Repasen las clases 1 a 3', materia_id: '__materiaSociales__', grado: 3, grupo: 'A', material_ids: ['__materialId__'] },
          tests: `
pm.test("Responde 201", () => pm.response.to.have.status(201));
pm.test("Lleva el material para estudiar", () => pm.expect(pm.response.json().materiales).to.have.lengthOf(1));` }),
        req('Subir imagen para un juego', 'POST', '/api/juegos/imagenes', {
          quien: 'Profesor',
          form: [{ key: 'imagen', type: 'file', src: 'archivos-prueba/vaca.png' }],
          tests: `
pm.test("Responde 201", () => pm.response.to.have.status(201));
pm.collectionVariables.set("imagenArchivo", pm.response.json().archivo);` }),
        req('Crear juego (une palabra e imagen)', 'POST', '/api/juegos', {
          quien: 'Profesor',
          body: { tipo: 'unir', titulo: 'Animales de la granja', materia_id: '__materiaMate__', grado: 3, grupo: 'A', parejas: [
            { palabra: 'Vaca', archivo: '{{imagenArchivo}}' }, { palabra: 'Gallina', imagen: '🐔' }] },
          tests: `
pm.test("Responde 201", () => pm.response.to.have.status(201));
pm.test("Es del profesor", () => pm.expect(pm.response.json().de_profesor).to.eql(true));` }),
        req('Resumen del inicio', 'GET', '/api/profesor/resumen?materia_id={{materiaMate}}&grado=3&grupo=A', { quien: 'Profesor', tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
const r = pm.response.json();
pm.test("Cuenta materiales y juegos", () => { pm.expect(r.materiales).to.be.above(0); pm.expect(r.juegos).to.be.above(0); });` }),
        req('Ocultar material', 'PATCH', '/api/materiales/{{materialId}}', { quien: 'Profesor', body: { visible: false }, tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
pm.test("Queda oculto", () => pm.expect(pm.response.json().visible).to.eql(false));` }),
        req('Volver a mostrar material', 'PATCH', '/api/materiales/{{materialId}}', { quien: 'Profesor', body: { visible: true }, tests: `
pm.test("Queda visible", () => pm.expect(pm.response.json().visible).to.eql(true));` }),
        req('Actualizar archivo (nueva versión)', 'PUT', '/api/materiales/{{materialId}}/archivo', {
          quien: 'Profesor',
          form: [{ key: 'archivo', type: 'file', src: 'archivos-prueba/tablas_del_2.pdf' }],
          tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
pm.test("Sube a versión 2", () => pm.expect(pm.response.json().version).to.eql(2));` }),
        req('Agregar dato curioso', 'POST', '/api/datos', { quien: 'Profesor',
          body: { materia_id: '__materiaSociales__', texto: 'Bogotá se fundó en 1538.', enlace: 'https://es.wikipedia.org/wiki/Bogot%C3%A1' },
          tests: 'pm.test("Responde 201", () => pm.response.to.have.status(201));' }),
      ],
    },
    {
      name: '3. Estudiante',
      item: [
        req('Actualizar (sincronizar todo)', 'GET', '/api/estudiante/sync', { quien: 'Estudiante', tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
const s = pm.response.json();
pm.test("Trae material, pruebas, parciales y juegos", () => {
  pm.expect(s.materiales).to.be.an("array");
  pm.expect(s.pruebas.length).to.be.above(0);
  pm.expect(s.parciales.length).to.be.above(0);
  pm.expect(s.juegos.length).to.be.above(0);
});
pm.test("Límite de 10 descargas", () => pm.expect(s.descargas.maximo).to.eql(10));` }),
        req('Descargar material', 'GET', '/api/materiales/{{materialId}}/descargar', { quien: 'Estudiante', tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
pm.test("Envía la versión", () => pm.expect(pm.response.headers.get("X-Version")).to.eql("2"));` }),
        req('Responder prueba (en línea)', 'POST', '/api/pruebas/{{pruebaId}}/responder', { quien: 'Estudiante', body: { respuestas: [0, 1] }, tests: `
pm.test("Responde 201", () => pm.response.to.have.status(201));
pm.test("2 de 2 correctas", () => { pm.expect(pm.response.json().correctas).to.eql(2); pm.expect(pm.response.json().total).to.eql(2); });` }),
        req('Enviar lo hecho sin internet', 'POST', '/api/estudiante/enviar', { quien: 'Estudiante',
          body: { resultados: [{ prueba_id: '__pruebaId__', respuestas: [1, 1], presentada_en: '2026-10-12T15:00:00Z' }], quitadas: [] },
          tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
pm.test("No cambia la primera nota", () => { pm.expect(pm.response.json().resultados[0].nuevo).to.eql(false); pm.expect(pm.response.json().resultados[0].correctas).to.eql(2); });` }),
        req('Estudiante intenta publicar (debe fallar)', 'POST', '/api/pruebas', { quien: 'Estudiante',
          body: { titulo: 'X', materia_id: 1, grado: 3, grupo: 'A', preguntas: [] },
          tests: 'pm.test("Responde 403", () => pm.response.to.have.status(403));' }),
      ],
    },
    {
      name: '4. Seguimiento del profesor',
      item: [
        req('Resultados de la prueba', 'GET', '/api/pruebas/{{pruebaId}}/resultados', { quien: 'Profesor', tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
const r = pm.response.json();
pm.test("Al menos 1 la presentó", () => pm.expect(r.presentaron).to.be.above(0));
pm.test("Trae nota por estudiante", () => pm.expect(r.por_estudiante).to.be.an("array"));` }),
        req('Quién descargó qué', 'GET', '/api/profesor/descargas?grado=3&grupo=A', { quien: 'Profesor', tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
pm.test("Ana aparece con descarga activa", () => pm.expect(pm.response.json().filas.some(f => f.nombre === "Ana Pérez" && f.estado === "activa")).to.eql(true));` }),
        req('Estudiante quita la descarga', 'POST', '/api/descargas/{{materialId}}/quitar', { quien: 'Estudiante', tests: `
pm.test("Responde 200", () => pm.response.to.have.status(200));
pm.test("Queda quitada", () => pm.expect(pm.response.json().quitada).to.eql(true));` }),
        req('Sin token (debe fallar)', 'GET', '/api/profesor/descargas?grado=3&grupo=A', {
          tests: 'pm.test("Responde 401", () => pm.response.to.have.status(401));' }),
      ],
    },
  ],
};

const destino = path.join(__dirname, '..', 'postman', 'Biblioteca.postman_collection.json');
fs.mkdirSync(path.dirname(destino), { recursive: true });
fs.writeFileSync(destino, JSON.stringify(coleccion, null, 2));
console.log('Colección generada en', destino);
