// Base de datos SQLite (módulo incluido en Node: node:sqlite). Un solo archivo: data/biblioteca.db
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

const ESQUEMA = `
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS usuarios (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  nombre      TEXT NOT NULL,
  correo      TEXT NOT NULL UNIQUE,
  clave_hash  TEXT NOT NULL,
  rol         TEXT NOT NULL CHECK (rol IN ('profesor','estudiante')),
  grado       INTEGER CHECK (grado BETWEEN 1 AND 11),
  grupo       TEXT,
  creado_en   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS materias (
  id      INTEGER PRIMARY KEY AUTOINCREMENT,
  nombre  TEXT NOT NULL UNIQUE,
  color   TEXT NOT NULL,
  icono   TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS materiales (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  profesor_id       INTEGER NOT NULL REFERENCES usuarios(id),
  materia_id        INTEGER NOT NULL REFERENCES materias(id),
  grado             INTEGER NOT NULL,
  grupo             TEXT NOT NULL,
  titulo            TEXT NOT NULL,
  tipo              TEXT NOT NULL CHECK (tipo IN ('pdf','video','imagen','examen','formulario','taller')),
  archivo           TEXT NOT NULL,
  archivo_original  TEXT NOT NULL,
  mime              TEXT NOT NULL,
  tamano            INTEGER NOT NULL,
  version           INTEGER NOT NULL DEFAULT 1,
  visible           INTEGER NOT NULL DEFAULT 1,
  borrado           INTEGER NOT NULL DEFAULT 0,
  creado_en         TEXT NOT NULL DEFAULT (datetime('now')),
  actualizado_en    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS pruebas (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  profesor_id  INTEGER NOT NULL REFERENCES usuarios(id),
  materia_id   INTEGER NOT NULL REFERENCES materias(id),
  grado        INTEGER NOT NULL,
  grupo        TEXT NOT NULL,
  titulo       TEXT NOT NULL,
  creado_en    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS preguntas (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  prueba_id  INTEGER NOT NULL REFERENCES pruebas(id) ON DELETE CASCADE,
  orden      INTEGER NOT NULL,
  enunciado  TEXT NOT NULL,
  opciones   TEXT NOT NULL,          -- JSON: ["1789","1810","1492"]
  correcta   INTEGER NOT NULL        -- índice de la opción correcta (0..n-1)
);

CREATE TABLE IF NOT EXISTS resultados (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  prueba_id      INTEGER NOT NULL REFERENCES pruebas(id) ON DELETE CASCADE,
  estudiante_id  INTEGER NOT NULL REFERENCES usuarios(id),
  correctas      INTEGER NOT NULL,
  total          INTEGER NOT NULL,
  respuestas     TEXT NOT NULL,      -- JSON: [0,2,1]
  presentada_en  TEXT NOT NULL,
  recibida_en    TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (prueba_id, estudiante_id)
);

CREATE TABLE IF NOT EXISTS parciales (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  profesor_id  INTEGER NOT NULL REFERENCES usuarios(id),
  materia_id   INTEGER NOT NULL REFERENCES materias(id),
  grado        INTEGER NOT NULL,
  grupo        TEXT NOT NULL,
  titulo       TEXT NOT NULL,
  fecha        TEXT NOT NULL,        -- YYYY-MM-DD
  aviso        TEXT NOT NULL DEFAULT '',
  creado_en    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS parcial_materiales (
  parcial_id   INTEGER NOT NULL REFERENCES parciales(id) ON DELETE CASCADE,
  material_id  INTEGER NOT NULL REFERENCES materiales(id),
  PRIMARY KEY (parcial_id, material_id)
);

CREATE TABLE IF NOT EXISTS descargas (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  estudiante_id  INTEGER NOT NULL REFERENCES usuarios(id),
  material_id    INTEGER NOT NULL REFERENCES materiales(id),
  estado         TEXT NOT NULL CHECK (estado IN ('activa','quitada')),
  descargado_en  TEXT NOT NULL DEFAULT (datetime('now')),
  quitado_en     TEXT,
  UNIQUE (estudiante_id, material_id)
);

CREATE TABLE IF NOT EXISTS juegos (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  profesor_id  INTEGER REFERENCES usuarios(id),   -- NULL = juego incluido en la app
  materia_id   INTEGER NOT NULL REFERENCES materias(id),
  grado        INTEGER,                         -- NULL = para todos los grados
  grupo        TEXT,
  tipo         TEXT NOT NULL CHECK (tipo IN ('memoria','unir','reloj')),
  titulo       TEXT NOT NULL,
  parejas      TEXT NOT NULL,                   -- JSON: [{"palabra":"Vaca","imagen":"🐄","archivo":null}]
  creado_en    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS datos_curiosos (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  materia_id   INTEGER NOT NULL REFERENCES materias(id),
  profesor_id  INTEGER REFERENCES usuarios(id),
  texto        TEXT NOT NULL,
  enlace       TEXT
);

CREATE INDEX IF NOT EXISTS ix_materiales_grupo ON materiales(materia_id, grado, grupo);
CREATE INDEX IF NOT EXISTS ix_pruebas_grupo ON pruebas(materia_id, grado, grupo);
CREATE INDEX IF NOT EXISTS ix_descargas_est ON descargas(estudiante_id, estado);
`;

const MATERIAS = [
  ['Matemáticas', '#FF6B4A', '🔢'],
  ['Inglés', '#4A90FF', '🔤'],
  ['Ciencias', '#2DBE6C', '🔬'],
  ['Sociales', '#FFB020', '🌎'],
  ['Lenguaje', '#A259FF', '📖'],
  ['Arte', '#FF4FA3', '🎨'],
];

const JUEGOS_INCLUIDOS = [
  ['Matemáticas', 'memoria', 'Memoria de parejas', [
    { palabra: '2 x 3', imagen: '6' }, { palabra: '2 x 4', imagen: '8' }, { palabra: '3 x 3', imagen: '9' },
    { palabra: '5 x 2', imagen: '10' }, { palabra: '4 x 3', imagen: '12' }, { palabra: '3 x 5', imagen: '15' }]],
  ['Sociales', 'reloj', 'Quiz contra el reloj', [
    { palabra: 'Revolución Francesa', imagen: '1789' }, { palabra: 'Independencia de Colombia', imagen: '1810' },
    { palabra: 'Llegada de Colón a América', imagen: '1492' }, { palabra: 'Batalla de Boyacá', imagen: '1819' },
    { palabra: 'Constitución de Colombia', imagen: '1991' }]],
  ['Ciencias', 'unir', 'Une el animal', [
    { palabra: 'Vaca', imagen: '🐄' }, { palabra: 'Gallina', imagen: '🐔' }, { palabra: 'Cerdo', imagen: '🐖' },
    { palabra: 'Caballo', imagen: '🐎' }, { palabra: 'Oveja', imagen: '🐑' }]],
  ['Inglés', 'memoria', 'Colors in English', [
    { palabra: 'Red', imagen: '🟥' }, { palabra: 'Blue', imagen: '🟦' }, { palabra: 'Green', imagen: '🟩' },
    { palabra: 'Yellow', imagen: '🟨' }, { palabra: 'Purple', imagen: '🟪' }, { palabra: 'Orange', imagen: '🟧' }]],
];

const DATOS = [
  ['Sociales', 'La Revolución Francesa empezó en 1789.', 'https://es.wikipedia.org/wiki/Revoluci%C3%B3n_francesa'],
  ['Ciencias', 'El agua puede ser sólido, líquido o gas.', 'https://es.wikipedia.org/wiki/Ciclo_hidrol%C3%B3gico'],
  ['Matemáticas', 'El cero lo usaban los mayas hace más de 1.500 años.', 'https://es.wikipedia.org/wiki/Numeraci%C3%B3n_maya'],
  ['Inglés', 'El inglés es el idioma oficial de más de 50 países.', null],
  ['Lenguaje', 'La letra más usada en español es la "e".', null],
  ['Arte', 'Mezclando azul y amarillo obtienes verde.', 'https://es.wikipedia.org/wiki/Color'],
];

function abrir(rutaArchivo) {
  if (rutaArchivo !== ':memory:') fs.mkdirSync(path.dirname(rutaArchivo), { recursive: true });
  const db = new DatabaseSync(rutaArchivo);
  db.exec(ESQUEMA);
  sembrar(db);
  return db;
}

function sembrar(db) {
  const hay = db.prepare('SELECT COUNT(*) AS n FROM materias').get().n;
  if (hay > 0) return;
  const insM = db.prepare('INSERT INTO materias (nombre, color, icono) VALUES (?, ?, ?)');
  for (const m of MATERIAS) insM.run(...m);
  const idMateria = (n) => db.prepare('SELECT id FROM materias WHERE nombre = ?').get(n).id;
  const insJ = db.prepare('INSERT INTO juegos (materia_id, tipo, titulo, parejas) VALUES (?, ?, ?, ?)');
  for (const [m, tipo, titulo, parejas] of JUEGOS_INCLUIDOS) insJ.run(idMateria(m), tipo, titulo, JSON.stringify(parejas));
  const insD = db.prepare('INSERT INTO datos_curiosos (materia_id, texto, enlace) VALUES (?, ?, ?)');
  for (const [m, texto, enlace] of DATOS) insD.run(idMateria(m), texto, enlace);
}

// Ejecuta fn dentro de una transacción
function transaccion(db, fn) {
  db.exec('BEGIN');
  try {
    const r = fn();
    db.exec('COMMIT');
    return r;
  } catch (e) {
    db.exec('ROLLBACK');
    throw e;
  }
}

module.exports = { abrir, transaccion };
