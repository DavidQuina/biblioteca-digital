// Rutas de la API de la Biblioteca Digital.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { crearApp, HttpError, tipoMime } = require('./framework');
const { transaccion } = require('./db');
const { hashClave, verificarClave, firmarToken, crearMiddlewares } = require('./auth');
const v = require('./validar');

function construirApp({ db, secreto, carpetaArchivos }) {
  const app = crearApp();
  const { autenticado, soloProfesor, soloEstudiante } = crearMiddlewares(db, secreto);
  const carpetaMateriales = path.join(carpetaArchivos, 'materiales');
  const carpetaImagenes = path.join(carpetaArchivos, 'imagenes');
  fs.mkdirSync(carpetaMateriales, { recursive: true });
  fs.mkdirSync(carpetaImagenes, { recursive: true });
  app.estatico('/imagenes/', carpetaImagenes);

  // ---------- utilidades ----------
  const uno = (sql, ...p) => db.prepare(sql).get(...p);
  const todos = (sql, ...p) => db.prepare(sql).all(...p);
  const correr = (sql, ...p) => db.prepare(sql).run(...p);
  const json = (t) => JSON.parse(t);

  const materia = (id) => {
    const m = uno('SELECT * FROM materias WHERE id = ?', v.entero(id, 'materia_id'));
    if (!m) throw new HttpError(404, 'La materia no existe');
    return m;
  };

  // Busca un registro del profesor actual (404 si no existe, 403 si es de otro profesor)
  const propio = (tabla, id, req, extra = '') => {
    const fila = uno(`SELECT * FROM ${tabla} WHERE id = ? ${extra}`, v.entero(id, 'id'));
    if (!fila) throw new HttpError(404, 'No se encontró el registro');
    if (fila.profesor_id !== req.usuario.id) throw new HttpError(403, 'Este contenido es de otro profesor');
    return fila;
  };

  const guardarArchivo = (archivo, carpeta) => {
    const ext = path.extname(archivo.nombre).toLowerCase().slice(0, 10);
    const nombre = crypto.randomBytes(12).toString('hex') + ext;
    fs.writeFileSync(path.join(carpeta, nombre), archivo.datos);
    return nombre;
  };
  const borrarArchivo = (nombre, carpeta) => { try { fs.unlinkSync(path.join(carpeta, nombre)); } catch { /* ya no existe */ } };

  // Filtros opcionales ?materia_id=&grado=&grupo= para las listas del profesor
  const filtrosProfesor = (req) => {
    const w = ['profesor_id = :prof'];
    const p = { prof: req.usuario.id };
    if (req.query.materia_id) { w.push('materia_id = :materia'); p.materia = v.entero(req.query.materia_id, 'materia_id'); }
    if (req.query.grado) { w.push('grado = :grado'); p.grado = v.grado(req.query.grado); }
    if (req.query.grupo) { w.push('grupo = :grupo'); p.grupo = v.grupo(req.query.grupo); }
    return { where: w.join(' AND '), p };
  };

  const quitarDescargasDe = (materialId) =>
    correr("UPDATE descargas SET estado = 'quitada', quitado_en = datetime('now') WHERE material_id = ? AND estado = 'activa'", materialId);

  const materialPublico = (m) => ({
    id: m.id, materia_id: m.materia_id, grado: m.grado, grupo: m.grupo, titulo: m.titulo, tipo: m.tipo,
    archivo_original: m.archivo_original, mime: m.mime, tamano: m.tamano, version: m.version,
    visible: !!m.visible, creado_en: m.creado_en, actualizado_en: m.actualizado_en,
  });

  const pruebaConPreguntas = (p) => ({
    ...p,
    preguntas: todos('SELECT id, orden, enunciado, opciones, correcta FROM preguntas WHERE prueba_id = ? ORDER BY orden', p.id)
      .map((q) => ({ ...q, opciones: json(q.opciones) })),
  });

  const parcialCompleto = (pc, soloVisibles = false) => ({
    ...pc,
    materiales: todos(`SELECT m.id, m.titulo, m.tipo FROM parcial_materiales pm JOIN materiales m ON m.id = pm.material_id
                       WHERE pm.parcial_id = ? AND m.borrado = 0 ${soloVisibles ? 'AND m.visible = 1' : ''}`, pc.id),
  });

  const juegoPublico = (j) => ({ ...j, parejas: json(j.parejas), de_profesor: j.profesor_id !== null });

  const calificar = (prueba, respuestas) => {
    const preguntas = todos('SELECT correcta FROM preguntas WHERE prueba_id = ? ORDER BY orden', prueba.id);
    if (!Array.isArray(respuestas) || respuestas.length !== preguntas.length) {
      v.falla(`"respuestas" debe tener ${preguntas.length} elementos (uno por pregunta)`);
    }
    const correctas = preguntas.reduce((n, q, i) => n + (Number(respuestas[i]) === q.correcta ? 1 : 0), 0);
    return { correctas, total: preguntas.length };
  };

  // ---------- generales ----------
  app.get('/api/salud', (req, res) => res.ok({ estado: 'ok', servicio: 'Biblioteca Digital API', hora: new Date().toISOString() }));

  app.get('/api/materias', (req, res) => res.ok(todos('SELECT * FROM materias ORDER BY id')));

  // ---------- autenticación ----------
  app.post('/api/auth/registro', (req, res) => {
    const b = req.body;
    const rol = v.texto(b.rol, 'rol', { max: 20 }).toLowerCase();
    if (!['profesor', 'estudiante'].includes(rol)) v.falla('"rol" debe ser profesor o estudiante');
    const nombre = v.texto(b.nombre, 'nombre', { max: 80 });
    const correo = v.correo(b.correo);
    const clave = v.texto(b.clave, 'clave', { max: 100 });
    if (clave.length < 6) v.falla('La contraseña debe tener al menos 6 caracteres');
    let grado = null; let grupo = null;
    if (rol === 'estudiante') { grado = v.grado(b.grado); grupo = v.grupo(b.grupo); }
    if (uno('SELECT id FROM usuarios WHERE correo = ?', correo)) throw new HttpError(409, 'Ya existe una cuenta con ese correo');
    const r = correr('INSERT INTO usuarios (nombre, correo, clave_hash, rol, grado, grupo) VALUES (?, ?, ?, ?, ?, ?)',
      nombre, correo, hashClave(clave), rol, grado, grupo);
    const usuario = uno('SELECT id, nombre, correo, rol, grado, grupo FROM usuarios WHERE id = ?', r.lastInsertRowid);
    res.creado({ token: firmarToken({ sub: usuario.id, rol }, secreto), usuario: { ...usuario } });
  });

  app.post('/api/auth/login', (req, res) => {
    const correo = v.correo(req.body.correo);
    const clave = v.texto(req.body.clave, 'clave', { max: 100 });
    const u = uno('SELECT * FROM usuarios WHERE correo = ?', correo);
    if (!u || !verificarClave(clave, u.clave_hash)) throw new HttpError(401, 'Correo o contraseña incorrectos');
    if (req.body.rol && req.body.rol !== u.rol) {
      throw new HttpError(403, `Esta cuenta es de ${u.rol}. Vuelve y elige el rol correcto.`);
    }
    const { clave_hash, creado_en, ...usuario } = u;
    res.ok({ token: firmarToken({ sub: u.id, rol: u.rol }, secreto), usuario: { ...usuario } });
  });

  app.get('/api/auth/yo', autenticado, (req, res) => res.ok(req.usuario));

  // =====================================================================
  //                              PROFESOR
  // =====================================================================

  // Pantalla 1: resumen de la materia y grupo elegidos
  app.get('/api/profesor/resumen', soloProfesor, (req, res) => {
    const materiaId = materia(req.query.materia_id).id;
    const grado = v.grado(req.query.grado);
    const grupo = v.grupo(req.query.grupo);
    const p = [req.usuario.id, materiaId, grado, grupo];
    const filtro = 'profesor_id = ? AND materia_id = ? AND grado = ? AND grupo = ?';
    const ultimos = todos(`
      SELECT 'material' AS clase, id, titulo, tipo, creado_en FROM materiales WHERE ${filtro} AND borrado = 0
      UNION ALL SELECT 'prueba', id, titulo, 'prueba', creado_en FROM pruebas WHERE ${filtro}
      UNION ALL SELECT 'parcial', id, titulo, 'parcial', creado_en FROM parciales WHERE ${filtro}
      UNION ALL SELECT 'juego', id, titulo, tipo, creado_en FROM juegos WHERE ${filtro}
      ORDER BY creado_en DESC, id DESC LIMIT 5`, ...p, ...p, ...p, ...p);
    res.ok({
      materiales: uno(`SELECT COUNT(*) n FROM materiales WHERE ${filtro} AND borrado = 0`, ...p).n,
      pruebas: uno(`SELECT COUNT(*) n FROM pruebas WHERE ${filtro}`, ...p).n,
      parciales: uno(`SELECT COUNT(*) n FROM parciales WHERE ${filtro}`, ...p).n,
      juegos: uno(`SELECT COUNT(*) n FROM juegos WHERE ${filtro}`, ...p).n,
      estudiantes: uno("SELECT COUNT(*) n FROM usuarios WHERE rol = 'estudiante' AND grado = ? AND grupo = ?", grado, grupo).n,
      ultimos,
    });
  });

  // Pantalla 2: subir material (multipart: titulo, tipo, materia_id, grado, grupo, archivo)
  app.post('/api/materiales', soloProfesor, (req, res) => {
    const b = req.body;
    const titulo = v.texto(b.titulo, 'titulo', { max: 120 });
    const tipo = v.texto(b.tipo, 'tipo', { max: 20 }).toLowerCase();
    if (!v.TIPOS_MATERIAL.includes(tipo)) v.falla(`"tipo" debe ser uno de: ${v.TIPOS_MATERIAL.join(', ')}`);
    const materiaId = materia(b.materia_id).id;
    const grado = v.grado(b.grado);
    const grupo = v.grupo(b.grupo);
    const archivo = req.archivos.archivo;
    if (!archivo || !archivo.datos.length) v.falla('Adjunta el archivo en el campo "archivo"');
    const nombre = guardarArchivo(archivo, carpetaMateriales);
    const mime = archivo.mime !== 'application/octet-stream' ? archivo.mime : tipoMime(archivo.nombre);
    const r = correr(`INSERT INTO materiales (profesor_id, materia_id, grado, grupo, titulo, tipo, archivo, archivo_original, mime, tamano)
                      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      req.usuario.id, materiaId, grado, grupo, titulo, tipo, nombre, archivo.nombre, mime, archivo.datos.length);
    res.creado(materialPublico(uno('SELECT * FROM materiales WHERE id = ?', r.lastInsertRowid)));
  });

  // Pantalla 5: lista de material del profesor
  app.get('/api/materiales', soloProfesor, (req, res) => {
    const { where, p } = filtrosProfesor(req);
    res.ok(todos(`SELECT m.*, ma.nombre AS materia FROM materiales m JOIN materias ma ON ma.id = m.materia_id
                  WHERE ${where.replace(/(\w+_id|grado|grupo) =/g, 'm.$1 =')} AND m.borrado = 0 ORDER BY m.creado_en DESC, m.id DESC`, p)
      .map((m) => ({ ...materialPublico(m), materia: m.materia })));
  });

  // Pantalla 5: visible / oculto y cambio de título
  app.patch('/api/materiales/:id', soloProfesor, (req, res) => {
    const m = propio('materiales', req.params.id, req, 'AND borrado = 0');
    const visible = req.body.visible === undefined ? !!m.visible : v.booleano(req.body.visible, 'visible');
    const titulo = req.body.titulo === undefined ? m.titulo : v.texto(req.body.titulo, 'titulo', { max: 120 });
    transaccion(db, () => {
      correr("UPDATE materiales SET visible = ?, titulo = ?, actualizado_en = datetime('now') WHERE id = ?", visible ? 1 : 0, titulo, m.id);
      if (!visible) quitarDescargasDe(m.id); // oculto: el estudiante ya no lo tiene
    });
    res.ok(materialPublico(uno('SELECT * FROM materiales WHERE id = ?', m.id)));
  });

  // Pantalla 5: actualizar archivo (nueva versión)
  app.put('/api/materiales/:id/archivo', soloProfesor, (req, res) => {
    const m = propio('materiales', req.params.id, req, 'AND borrado = 0');
    const archivo = req.archivos.archivo;
    if (!archivo || !archivo.datos.length) v.falla('Adjunta el archivo nuevo en el campo "archivo"');
    const nombre = guardarArchivo(archivo, carpetaMateriales);
    const mime = archivo.mime !== 'application/octet-stream' ? archivo.mime : tipoMime(archivo.nombre);
    correr(`UPDATE materiales SET archivo = ?, archivo_original = ?, mime = ?, tamano = ?, version = version + 1,
            actualizado_en = datetime('now') WHERE id = ?`, nombre, archivo.nombre, mime, archivo.datos.length, m.id);
    borrarArchivo(m.archivo, carpetaMateriales);
    res.ok(materialPublico(uno('SELECT * FROM materiales WHERE id = ?', m.id)));
  });

  // Pantalla 5: borrar
  app.delete('/api/materiales/:id', soloProfesor, (req, res) => {
    const m = propio('materiales', req.params.id, req, 'AND borrado = 0');
    transaccion(db, () => {
      correr("UPDATE materiales SET borrado = 1, visible = 0, actualizado_en = datetime('now') WHERE id = ?", m.id);
      quitarDescargasDe(m.id);
      correr('DELETE FROM parcial_materiales WHERE material_id = ?', m.id);
    });
    borrarArchivo(m.archivo, carpetaMateriales);
    res.ok({ borrado: true, id: m.id });
  });

  // Pantalla 3: crear prueba
  app.post('/api/pruebas', soloProfesor, (req, res) => {
    const b = req.body;
    const titulo = v.texto(b.titulo, 'titulo', { max: 120 });
    const materiaId = materia(b.materia_id).id;
    const grado = v.grado(b.grado);
    const grupo = v.grupo(b.grupo);
    if (!Array.isArray(b.preguntas) || b.preguntas.length < 1 || b.preguntas.length > 50) v.falla('La prueba debe tener entre 1 y 50 preguntas');
    const preguntas = b.preguntas.map((q, i) => {
      const enunciado = v.texto(q?.enunciado, `preguntas[${i}].enunciado`, { max: 300 });
      if (!Array.isArray(q.opciones) || q.opciones.length < 2 || q.opciones.length > 4) v.falla(`La pregunta ${i + 1} debe tener entre 2 y 4 opciones`);
      const opciones = q.opciones.map((o, j) => v.texto(o, `preguntas[${i}].opciones[${j}]`, { max: 150 }));
      const correcta = v.entero(q.correcta, `preguntas[${i}].correcta`, { min: 0, max: opciones.length - 1 });
      return { enunciado, opciones, correcta };
    });
    const id = transaccion(db, () => {
      const r = correr('INSERT INTO pruebas (profesor_id, materia_id, grado, grupo, titulo) VALUES (?, ?, ?, ?, ?)',
        req.usuario.id, materiaId, grado, grupo, titulo);
      const ins = db.prepare('INSERT INTO preguntas (prueba_id, orden, enunciado, opciones, correcta) VALUES (?, ?, ?, ?, ?)');
      preguntas.forEach((q, i) => ins.run(r.lastInsertRowid, i + 1, q.enunciado, JSON.stringify(q.opciones), q.correcta));
      return r.lastInsertRowid;
    });
    res.creado(pruebaConPreguntas(uno('SELECT * FROM pruebas WHERE id = ?', id)));
  });

  app.get('/api/pruebas', soloProfesor, (req, res) => {
    const { where, p } = filtrosProfesor(req);
    res.ok(todos(`SELECT p.*, (SELECT COUNT(*) FROM preguntas q WHERE q.prueba_id = p.id) AS num_preguntas,
                  (SELECT COUNT(*) FROM resultados r WHERE r.prueba_id = p.id) AS presentaron
                  FROM pruebas p WHERE ${where} ORDER BY creado_en DESC, id DESC`, p));
  });

  app.get('/api/pruebas/:id', soloProfesor, (req, res) => {
    res.ok(pruebaConPreguntas(propio('pruebas', req.params.id, req)));
  });

  app.delete('/api/pruebas/:id', soloProfesor, (req, res) => {
    const p = propio('pruebas', req.params.id, req);
    correr('DELETE FROM pruebas WHERE id = ?', p.id);
    res.ok({ borrado: true, id: p.id });
  });

  // Pantalla 7: resultados de una prueba
  app.get('/api/pruebas/:id/resultados', soloProfesor, (req, res) => {
    const p = propio('pruebas', req.params.id, req);
    const totalPreguntas = uno('SELECT COUNT(*) n FROM preguntas WHERE prueba_id = ?', p.id).n;
    const filas = todos(`SELECT u.id AS estudiante_id, u.nombre, r.correctas, r.total, r.presentada_en
                         FROM usuarios u LEFT JOIN resultados r ON r.estudiante_id = u.id AND r.prueba_id = ?
                         WHERE u.rol = 'estudiante' AND u.grado = ? AND u.grupo = ?
                         ORDER BY (r.id IS NULL), u.nombre`, p.id, p.grado, p.grupo);
    const hechas = filas.filter((f) => f.correctas !== null);
    const promedio = hechas.length ? Math.round((hechas.reduce((s, f) => s + f.correctas, 0) / hechas.length) * 10) / 10 : null;
    res.ok({
      prueba: { id: p.id, titulo: p.titulo, grado: p.grado, grupo: p.grupo, total_preguntas: totalPreguntas },
      estudiantes_grupo: filas.length,
      presentaron: hechas.length,
      promedio,
      por_estudiante: filas.map((f) => ({ ...f, falta: f.correctas === null })),
    });
  });

  // Pantalla 4: programar parcial
  app.post('/api/parciales', soloProfesor, (req, res) => {
    const b = req.body;
    const titulo = v.texto(b.titulo, 'titulo', { max: 120 });
    const fecha = v.fecha(b.fecha);
    const aviso = v.texto(b.aviso, 'aviso', { max: 500, opcional: true });
    const materiaId = materia(b.materia_id).id;
    const grado = v.grado(b.grado);
    const grupo = v.grupo(b.grupo);
    const ids = b.material_ids === undefined ? [] : b.material_ids;
    if (!Array.isArray(ids)) v.falla('"material_ids" debe ser una lista');
    for (const mid of ids) {
      const m = uno('SELECT * FROM materiales WHERE id = ? AND borrado = 0', v.entero(mid, 'material_ids'));
      if (!m || m.profesor_id !== req.usuario.id) v.falla(`El material ${mid} no existe o no es tuyo`);
      if (m.grado !== grado || m.grupo !== grupo) v.falla(`El material ${mid} es de otro grado o grupo`);
    }
    const id = transaccion(db, () => {
      const r = correr('INSERT INTO parciales (profesor_id, materia_id, grado, grupo, titulo, fecha, aviso) VALUES (?, ?, ?, ?, ?, ?, ?)',
        req.usuario.id, materiaId, grado, grupo, titulo, fecha, aviso);
      const ins = db.prepare('INSERT OR IGNORE INTO parcial_materiales (parcial_id, material_id) VALUES (?, ?)');
      ids.forEach((mid) => ins.run(r.lastInsertRowid, Number(mid)));
      return r.lastInsertRowid;
    });
    res.creado(parcialCompleto(uno('SELECT * FROM parciales WHERE id = ?', id)));
  });

  app.get('/api/parciales', soloProfesor, (req, res) => {
    const { where, p } = filtrosProfesor(req);
    res.ok(todos(`SELECT * FROM parciales WHERE ${where} ORDER BY fecha, id`, p).map((x) => parcialCompleto(x)));
  });

  app.delete('/api/parciales/:id', soloProfesor, (req, res) => {
    const p = propio('parciales', req.params.id, req);
    correr('DELETE FROM parciales WHERE id = ?', p.id);
    res.ok({ borrado: true, id: p.id });
  });

  // Pantalla 6: quién descargó qué
  app.get('/api/profesor/descargas', soloProfesor, (req, res) => {
    const grado = v.grado(req.query.grado);
    const grupo = v.grupo(req.query.grupo);
    const materiaId = req.query.materia_id ? materia(req.query.materia_id).id : null;
    const estudiantes = todos("SELECT id, nombre, correo FROM usuarios WHERE rol = 'estudiante' AND grado = ? AND grupo = ? ORDER BY nombre", grado, grupo);
    const filas = [];
    let conDescarga = 0;
    for (const e of estudiantes) {
      const ds = todos(`SELECT d.estado, d.descargado_en, d.quitado_en, m.id AS material_id, m.titulo, m.borrado
                        FROM descargas d JOIN materiales m ON m.id = d.material_id
                        WHERE d.estudiante_id = ? AND m.profesor_id = ? ${materiaId ? 'AND m.materia_id = ?' : ''}
                        ORDER BY d.descargado_en DESC`, e.id, req.usuario.id, ...(materiaId ? [materiaId] : []));
      if (ds.length) conDescarga++;
      if (!ds.length) filas.push({ estudiante_id: e.id, nombre: e.nombre, material_id: null, titulo: null, fecha: null, estado: 'pendiente' });
      for (const d of ds) {
        filas.push({ estudiante_id: e.id, nombre: e.nombre, material_id: d.material_id, titulo: d.titulo, fecha: d.descargado_en, estado: d.estado });
      }
    }
    res.ok({ grado, grupo, estudiantes: estudiantes.length, ya_descargaron: conDescarga, filas });
  });

  // Pantalla 8: juegos con su contenido
  app.post('/api/juegos/imagenes', soloProfesor, (req, res) => {
    const img = req.archivos.imagen;
    if (!img || !img.datos.length) v.falla('Adjunta la imagen en el campo "imagen"');
    if (!/^image\//.test(img.mime) && !/\.(png|jpe?g|gif|webp)$/i.test(img.nombre)) v.falla('El archivo debe ser una imagen');
    if (img.datos.length > 5 * 1024 * 1024) v.falla('La imagen debe pesar menos de 5 MB');
    const nombre = guardarArchivo(img, carpetaImagenes);
    res.creado({ archivo: nombre, url: `/imagenes/${nombre}` });
  });

  app.post('/api/juegos', soloProfesor, (req, res) => {
    const b = req.body;
    const tipo = v.texto(b.tipo, 'tipo', { max: 20 }).toLowerCase();
    if (!v.TIPOS_JUEGO.includes(tipo)) v.falla(`"tipo" debe ser uno de: ${v.TIPOS_JUEGO.join(', ')}`);
    const titulo = v.texto(b.titulo, 'titulo', { max: 120 });
    const materiaId = materia(b.materia_id).id;
    const grado = v.grado(b.grado);
    const grupo = v.grupo(b.grupo);
    if (!Array.isArray(b.parejas) || b.parejas.length < 2 || b.parejas.length > 12) v.falla('El juego necesita entre 2 y 12 parejas');
    const parejas = b.parejas.map((pj, i) => {
      const palabra = v.texto(pj?.palabra, `parejas[${i}].palabra`, { max: 60 });
      const imagen = v.texto(pj.imagen, `parejas[${i}].imagen`, { max: 60, opcional: true });
      const archivo = pj.archivo ? path.basename(String(pj.archivo)) : null;
      if (!imagen && !archivo) v.falla(`La pareja ${i + 1} necesita una imagen (emoji o foto)`);
      if (archivo && !fs.existsSync(path.join(carpetaImagenes, archivo))) v.falla(`La imagen de la pareja ${i + 1} no se ha subido`);
      return { palabra, imagen: imagen || null, archivo };
    });
    if (tipo === 'reloj' && parejas.length < 3) v.falla('El quiz contra el reloj necesita al menos 3 parejas');
    const r = correr('INSERT INTO juegos (profesor_id, materia_id, grado, grupo, tipo, titulo, parejas) VALUES (?, ?, ?, ?, ?, ?, ?)',
      req.usuario.id, materiaId, grado, grupo, tipo, titulo, JSON.stringify(parejas));
    res.creado(juegoPublico(uno('SELECT * FROM juegos WHERE id = ?', r.lastInsertRowid)));
  });

  app.get('/api/juegos', soloProfesor, (req, res) => {
    const { where, p } = filtrosProfesor(req);
    res.ok(todos(`SELECT * FROM juegos WHERE ${where} ORDER BY creado_en DESC, id DESC`, p).map(juegoPublico));
  });

  app.delete('/api/juegos/:id', soloProfesor, (req, res) => {
    const j = propio('juegos', req.params.id, req);
    correr('DELETE FROM juegos WHERE id = ?', j.id);
    res.ok({ borrado: true, id: j.id });
  });

  // Datos curiosos (los incluye la app y el profesor puede agregar más)
  app.get('/api/datos', autenticado, (req, res) => {
    const filtro = req.query.materia_id ? 'WHERE materia_id = ?' : '';
    res.ok(todos(`SELECT * FROM datos_curiosos ${filtro} ORDER BY id`, ...(filtro ? [v.entero(req.query.materia_id, 'materia_id')] : [])));
  });

  app.post('/api/datos', soloProfesor, (req, res) => {
    const materiaId = materia(req.body.materia_id).id;
    const textoDato = v.texto(req.body.texto, 'texto', { max: 300 });
    const enlace = v.texto(req.body.enlace, 'enlace', { max: 300, opcional: true }) || null;
    if (enlace && !/^https?:\/\//i.test(enlace)) v.falla('El enlace debe empezar por http:// o https://');
    const r = correr('INSERT INTO datos_curiosos (materia_id, profesor_id, texto, enlace) VALUES (?, ?, ?, ?)', materiaId, req.usuario.id, textoDato, enlace);
    res.creado(uno('SELECT * FROM datos_curiosos WHERE id = ?', r.lastInsertRowid));
  });

  app.delete('/api/datos/:id', soloProfesor, (req, res) => {
    const d = propio('datos_curiosos', req.params.id, req);
    correr('DELETE FROM datos_curiosos WHERE id = ?', d.id);
    res.ok({ borrado: true, id: d.id });
  });

  // =====================================================================
  //                              ESTUDIANTE
  // =====================================================================

  // Pantalla 8 (Actualizar): baja todo lo de su grado y grupo para usarlo sin internet
  app.get('/api/estudiante/sync', soloEstudiante, (req, res) => {
    const { id, grado, grupo } = req.usuario;
    const materiales = todos(`SELECT m.*, d.estado AS descarga_estado, d.descargado_en
                              FROM materiales m LEFT JOIN descargas d ON d.material_id = m.id AND d.estudiante_id = ?
                              WHERE m.grado = ? AND m.grupo = ? AND m.visible = 1 AND m.borrado = 0
                              ORDER BY m.materia_id, m.creado_en, m.id`, id, grado, grupo)
      .map((m) => ({ ...materialPublico(m), descarga: m.descarga_estado || null }));
    const pruebas = todos('SELECT * FROM pruebas WHERE grado = ? AND grupo = ? ORDER BY creado_en, id', grado, grupo)
      .map((p) => {
        const r = uno('SELECT correctas, total, presentada_en FROM resultados WHERE prueba_id = ? AND estudiante_id = ?', p.id, id);
        return { ...pruebaConPreguntas(p), mi_resultado: r ? { ...r } : null };
      });
    const parciales = todos('SELECT * FROM parciales WHERE grado = ? AND grupo = ? ORDER BY fecha, id', grado, grupo)
      .map((pc) => parcialCompleto(pc, true));
    const juegos = todos('SELECT * FROM juegos WHERE profesor_id IS NULL OR (grado = ? AND grupo = ?) ORDER BY (profesor_id IS NULL), id', grado, grupo)
      .map(juegoPublico);
    res.ok({
      hora_servidor: new Date().toISOString(),
      usuario: req.usuario,
      materias: todos('SELECT * FROM materias ORDER BY id'),
      materiales,
      pruebas,
      parciales,
      juegos,
      datos: todos('SELECT * FROM datos_curiosos ORDER BY materia_id, id'),
      descargas: {
        activas: uno("SELECT COUNT(*) n FROM descargas WHERE estudiante_id = ? AND estado = 'activa'", id).n,
        maximo: v.MAX_DESCARGAS_ACTIVAS,
      },
    });
  });

  // Pantalla 2 del estudiante: descargar (una sola vez, máximo 10 activas). El profesor dueño también puede bajarlo.
  app.get('/api/materiales/:id/descargar', autenticado, (req, res) => {
    const m = uno('SELECT * FROM materiales WHERE id = ? AND borrado = 0', v.entero(req.params.id, 'id'));
    if (!m) throw new HttpError(404, 'El material no existe');
    const ruta = path.join(carpetaMateriales, m.archivo);
    if (!fs.existsSync(ruta)) throw new HttpError(410, 'El archivo ya no está en el servidor');
    const cabeceras = {
      'X-Version': String(m.version),
      'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(m.archivo_original)}`,
    };
    const u = req.usuario;
    if (u.rol === 'profesor') {
      if (m.profesor_id !== u.id) throw new HttpError(403, 'Este material es de otro profesor');
      return res.enviarArchivo(ruta, m.mime, cabeceras);
    }
    if (m.grado !== u.grado || m.grupo !== u.grupo || !m.visible) throw new HttpError(403, 'Este material no está disponible para tu grupo');
    transaccion(db, () => {
      const d = uno('SELECT * FROM descargas WHERE estudiante_id = ? AND material_id = ?', u.id, m.id);
      if (d && d.estado === 'activa') return; // ya lo tenía: nueva versión o repetir no gasta cupo
      const activas = uno("SELECT COUNT(*) n FROM descargas WHERE estudiante_id = ? AND estado = 'activa'", u.id).n;
      if (activas >= v.MAX_DESCARGAS_ACTIVAS) {
        throw new HttpError(409, `Llegaste al máximo de ${v.MAX_DESCARGAS_ACTIVAS} descargas. Quita una para descargar otra.`);
      }
      if (d) correr("UPDATE descargas SET estado = 'activa', descargado_en = datetime('now'), quitado_en = NULL WHERE id = ?", d.id);
      else correr("INSERT INTO descargas (estudiante_id, material_id, estado) VALUES (?, ?, 'activa')", u.id, m.id);
    });
    res.enviarArchivo(ruta, m.mime, cabeceras);
  });

  // El estudiante quita una descarga para liberar cupo
  app.post('/api/descargas/:materialId/quitar', soloEstudiante, (req, res) => {
    const mid = v.entero(req.params.materialId, 'materialId');
    const r = correr("UPDATE descargas SET estado = 'quitada', quitado_en = datetime('now') WHERE estudiante_id = ? AND material_id = ? AND estado = 'activa'",
      req.usuario.id, mid);
    if (!r.changes) throw new HttpError(404, 'No tenías ese material descargado');
    res.ok({ quitada: true, material_id: mid, activas: uno("SELECT COUNT(*) n FROM descargas WHERE estudiante_id = ? AND estado = 'activa'", req.usuario.id).n });
  });

  // Responder una prueba estando en línea
  app.post('/api/pruebas/:id/responder', soloEstudiante, (req, res) => {
    const resultado = guardarResultado(req.usuario, { prueba_id: req.params.id, respuestas: req.body.respuestas, presentada_en: req.body.presentada_en });
    res.json(resultado.nuevo ? 201 : 200, resultado);
  });

  function guardarResultado(u, { prueba_id, respuestas, presentada_en }) {
    const p = uno('SELECT * FROM pruebas WHERE id = ?', v.entero(prueba_id, 'prueba_id'));
    if (!p) throw new HttpError(404, `La prueba ${prueba_id} ya no existe`);
    if (p.grado !== u.grado || p.grupo !== u.grupo) throw new HttpError(403, 'Esta prueba no es de tu grupo');
    const { correctas, total } = calificar(p, respuestas);
    const fechaPresentada = presentada_en && !Number.isNaN(Date.parse(presentada_en)) ? new Date(presentada_en).toISOString() : new Date().toISOString();
    const r = correr(`INSERT OR IGNORE INTO resultados (prueba_id, estudiante_id, correctas, total, respuestas, presentada_en)
                      VALUES (?, ?, ?, ?, ?, ?)`, p.id, u.id, correctas, total, JSON.stringify(respuestas.map(Number)), fechaPresentada);
    const guardado = uno('SELECT correctas, total, presentada_en FROM resultados WHERE prueba_id = ? AND estudiante_id = ?', p.id, u.id);
    return { prueba_id: p.id, nuevo: r.changes > 0, ...guardado };
  }

  // Pantalla 8: enviar al profesor lo que hizo sin internet (pruebas resueltas y descargas quitadas)
  app.post('/api/estudiante/enviar', soloEstudiante, (req, res) => {
    const resultados = req.body.resultados ?? [];
    const quitadas = req.body.quitadas ?? [];
    if (!Array.isArray(resultados) || !Array.isArray(quitadas)) v.falla('"resultados" y "quitadas" deben ser listas');
    const respuesta = { resultados: [], quitadas: [], errores: [] };
    for (const r of resultados) {
      try { respuesta.resultados.push(guardarResultado(req.usuario, r || {})); }
      catch (e) { respuesta.errores.push({ prueba_id: r?.prueba_id ?? null, error: e.message }); }
    }
    for (const mid of quitadas) {
      const id = Number(mid);
      if (!Number.isInteger(id)) { respuesta.errores.push({ material_id: mid, error: 'id inválido' }); continue; }
      correr("UPDATE descargas SET estado = 'quitada', quitado_en = datetime('now') WHERE estudiante_id = ? AND material_id = ? AND estado = 'activa'",
        req.usuario.id, id);
      respuesta.quitadas.push(id);
    }
    respuesta.descargas_activas = uno("SELECT COUNT(*) n FROM descargas WHERE estudiante_id = ? AND estado = 'activa'", req.usuario.id).n;
    res.ok(respuesta);
  });

  return app;
}

module.exports = { construirApp };
