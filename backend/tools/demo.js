// Carga datos de ejemplo para mostrar la app: node tools/demo.js
// Crea un profesor, 4 estudiantes de 3° A, material, una prueba, un parcial y un juego.
'use strict';
process.removeAllListeners('warning');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { abrir } = require('../src/db');
const { construirApp } = require('../src/app');

const CARPETA_DATOS = path.resolve(process.env.CARPETA_DATOS || path.join(__dirname, '..', 'data'));
fs.mkdirSync(CARPETA_DATOS, { recursive: true });
const rutaSecreto = path.join(CARPETA_DATOS, 'secreto.txt');
if (!process.env.JWT_SECRETO && !fs.existsSync(rutaSecreto)) fs.writeFileSync(rutaSecreto, crypto.randomBytes(32).toString('hex'));
const secreto = process.env.JWT_SECRETO || fs.readFileSync(rutaSecreto, 'utf8').trim();
const db = abrir(path.join(CARPETA_DATOS, 'biblioteca.db'));
const app = construirApp({ db, secreto, carpetaArchivos: path.join(CARPETA_DATOS, 'archivos') });
const archivos = path.join(__dirname, '..', 'postman', 'archivos-prueba');

(async () => {
  const servidor = await new Promise((ok) => { const s = app.escuchar(0, '127.0.0.1', () => ok(s)); });
  const base = `http://127.0.0.1:${servidor.address().port}`;
  const api = async (metodo, ruta, { token, json, form } = {}) => {
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    if (json) headers['Content-Type'] = 'application/json';
    const r = await fetch(base + ruta, { method: metodo, headers, body: json ? JSON.stringify(json) : form });
    const datos = await r.json();
    if (!r.ok) throw new Error(`${metodo} ${ruta}: ${datos.error}`);
    return datos;
  };
  const cuenta = async (datos) => {
    try { return (await api('POST', '/api/auth/registro', { json: datos })).token; }
    catch { return (await api('POST', '/api/auth/login', { json: { correo: datos.correo, clave: datos.clave } })).token; }
  };
  const subir = (token, campos, nombre, mime) => {
    const f = new FormData();
    Object.entries(campos).forEach(([k, v]) => f.append(k, String(v)));
    f.append('archivo', new Blob([fs.readFileSync(path.join(archivos, nombre))], { type: mime }), nombre);
    return api('POST', '/api/materiales', { token, form: f });
  };

  try {
    const profe = await cuenta({ rol: 'profesor', nombre: 'Profe Marta', correo: 'profe@correo.com', clave: '123456' });
    for (const [nombre, correo] of [['Ana Pérez', 'ana'], ['Luis Gómez', 'luis'], ['Sofía Díaz', 'sofia'], ['Pedro Ruiz', 'pedro']]) {
      await cuenta({ rol: 'estudiante', nombre, correo: `${correo}@correo.com`, clave: '123456', grado: 3, grupo: 'A' });
    }
    const materias = await api('GET', '/api/materias');
    const id = (n) => materias.find((m) => m.nombre === n).id;
    const g = { grado: 3, grupo: 'A' };

    const yaHay = (await api('GET', `/api/materiales?grado=3&grupo=A`, { token: profe })).length > 0;
    if (yaHay) {
      console.log('Los datos de ejemplo ya estaban cargados.');
    } else {
      const tablas = await subir(profe, { titulo: 'Las tablas del 2', tipo: 'pdf', materia_id: id('Matemáticas'), ...g }, 'tablas_del_2.pdf', 'application/pdf');
      await subir(profe, { titulo: 'Animales de la granja', tipo: 'imagen', materia_id: id('Ciencias'), ...g }, 'vaca.png', 'image/png');
      const revolucion = await subir(profe, { titulo: 'La Revolución Francesa', tipo: 'pdf', materia_id: id('Sociales'), ...g }, 'tablas_del_2.pdf', 'application/pdf');
      await api('POST', '/api/pruebas', { token: profe, json: {
        titulo: 'La Revolución Francesa', materia_id: id('Sociales'), ...g, preguntas: [
          { enunciado: '¿En qué año fue la Revolución Francesa?', opciones: ['1789', '1810', '1492'], correcta: 0 },
          { enunciado: '¿En qué país ocurrió?', opciones: ['España', 'Francia', 'Italia'], correcta: 1 },
          { enunciado: '¿Qué cayó el 14 de julio de 1789?', opciones: ['La Bastilla', 'Un puente', 'Una torre'], correcta: 0 },
        ] } });
      await api('POST', '/api/pruebas', { token: profe, json: {
        titulo: 'Tablas del 2', materia_id: id('Matemáticas'), ...g, preguntas: [
          { enunciado: '2 x 3 =', opciones: ['5', '6', '8'], correcta: 1 },
          { enunciado: '2 x 5 =', opciones: ['10', '12', '7'], correcta: 0 },
        ] } });
      await api('POST', '/api/parciales', { token: profe, json: {
        titulo: 'Parcial de Sociales', fecha: '2026-10-15', aviso: 'Repasen las clases 1 a 3. Traigan lápiz y borrador.',
        materia_id: id('Sociales'), ...g, material_ids: [revolucion.id] } });
      await api('POST', '/api/parciales', { token: profe, json: {
        titulo: 'Taller de multiplicar', fecha: '2026-10-22', aviso: 'Estudien las tablas del 2 y del 3.',
        materia_id: id('Matemáticas'), ...g, material_ids: [tablas.id] } });
      await api('POST', '/api/juegos', { token: profe, json: {
        tipo: 'unir', titulo: 'Animales de la granja', materia_id: id('Ciencias'), ...g,
        parejas: [{ palabra: 'Vaca', imagen: '🐄' }, { palabra: 'Gallina', imagen: '🐔' }, { palabra: 'Cerdo', imagen: '🐖' }, { palabra: 'Pato', imagen: '🦆' }] } });
      console.log('Datos de ejemplo cargados.');
    }
    console.log('\nCuentas (contraseña 123456):');
    console.log('  Profesor:    profe@correo.com');
    console.log('  Estudiantes: ana@correo.com, luis@correo.com, sofia@correo.com, pedro@correo.com  (3° A)');
  } catch (e) {
    console.error('Error cargando el ejemplo:', e.message);
    process.exitCode = 1;
  } finally {
    servidor.close();
  }
})();
