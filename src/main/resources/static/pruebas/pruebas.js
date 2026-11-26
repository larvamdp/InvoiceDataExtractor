/*
 * Página de pruebas interna: lógica (sin dependencias externas).
 * Todo lo que viene de la respuesta (valores extraídos del documento) se inserta con textContent,
 * nunca con innerHTML: una factura puede contener texto que parezca HTML.
 */
"use strict";

const NOMBRE_VALIDO = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
const TIPOS = ["TEXTO", "NUMERO", "IMPORTE", "FECHA", "CUIT"];
const ENDPOINT = "/api/v1/extracciones/sync";

const CATALOGO = {
  cabecera: [
    { nombre: "numeroComprobante", tipo: "TEXTO", marcado: true,
      etiquetas: ["Número factura", "Número de factura", "Nro. factura", "Factura N°", "Comp. Nro"],
      descripcion: "Número completo del comprobante, incluyendo punto de venta" },
    { nombre: "fechaEmision", tipo: "FECHA", marcado: true, etiquetas: ["Fecha", "Fecha de emisión"] },
    { nombre: "importeTotal", tipo: "IMPORTE", marcado: true, etiquetas: ["Total", "Importe total"] },
    { nombre: "tipoComprobante", tipo: "TEXTO", marcado: false,
      etiquetas: ["Factura A", "Factura B", "Factura C", "Nota de crédito", "Nota de débito", "Cód."],
      descripcion: "Tipo y letra del comprobante" },
    { nombre: "cuitEmisor", tipo: "CUIT", marcado: false, etiquetas: ["CUIT", "C.U.I.T."],
      descripcion: "CUIT de quien emite el comprobante" },
    { nombre: "razonSocialEmisor", tipo: "TEXTO", marcado: false,
      etiquetas: ["Razón social", "Razón Social", "Emisor"], descripcion: "Nombre de quien emite el comprobante" },
    { nombre: "cuitReceptor", tipo: "CUIT", marcado: false, etiquetas: ["CUIT cliente", "CUIT", "Cliente"],
      descripcion: "CUIT de quien recibe el comprobante" },
    { nombre: "importeNeto", tipo: "IMPORTE", marcado: false,
      etiquetas: ["Neto gravado", "Importe neto", "Subtotal"] },
    { nombre: "importeIva", tipo: "IMPORTE", marcado: false, etiquetas: ["IVA", "IVA 21%", "IVA 10,5%"],
      descripcion: "Importe total de IVA" },
    { nombre: "cae", tipo: "TEXTO", marcado: false, etiquetas: ["CAE", "C.A.E.", "Cód. autorización"] },
    { nombre: "fechaVencimientoCae", tipo: "FECHA", marcado: false,
      etiquetas: ["Vto. CAE", "Fecha de vto. de CAE", "Vencimiento CAE"] },
    { nombre: "fechaVencimiento", tipo: "FECHA", marcado: false,
      etiquetas: ["Vencimiento", "Fecha de vencimiento", "Vto. de pago"], descripcion: "Vencimiento del pago" },
    { nombre: "numeroOrdenCompra", tipo: "TEXTO", marcado: false,
      etiquetas: ["Orden de compra", "OC", "O.C. N°"] },
    { nombre: "moneda", tipo: "TEXTO", marcado: false, etiquetas: ["Moneda"] },
  ],
  detalle: [
    { nombre: "concepto", tipo: "TEXTO", marcado: true, etiquetas: ["Concepto", "Descripción", "Detalle"] },
    { nombre: "cantidad", tipo: "NUMERO", marcado: true, etiquetas: ["Cant.", "Cantidad"] },
    { nombre: "precioUnitario", tipo: "IMPORTE", marcado: true, etiquetas: ["P. Unit.", "Precio unitario"] },
    { nombre: "importe", tipo: "IMPORTE", marcado: false, etiquetas: ["Importe", "Subtotal", "Total"] },
    { nombre: "alicuotaIva", tipo: "NUMERO", marcado: false, etiquetas: ["Alic. IVA", "% IVA", "IVA"] },
  ],
};

const estado = {
  archivo: null,
  propios: { cabecera: [], detalle: [] },
  controlador: null,
  reloj: null,
};

const $ = (id) => document.getElementById(id);

function el(etiqueta, texto, clase) {
  const nodo = document.createElement(etiqueta);
  if (texto !== undefined && texto !== null) nodo.textContent = String(texto);
  if (clase) nodo.className = clase;
  return nodo;
}

function campos(seccion) {
  return CATALOGO[seccion].concat(estado.propios[seccion]);
}

/* ---------- Panel izquierdo ---------- */

function dibujarListas() {
  for (const seccion of ["cabecera", "detalle"]) {
    const lista = $("lista-" + seccion);
    lista.replaceChildren();
    for (const campo of campos(seccion)) {
      const li = el("li");
      const label = el("label");
      const casilla = document.createElement("input");
      casilla.type = "checkbox";
      casilla.checked = campo.marcado;
      casilla.addEventListener("change", () => { campo.marcado = casilla.checked; actualizarBotones(); });
      label.append(casilla, el("span", campo.nombre), el("span", campo.tipo, "tipo"));
      li.append(label);
      if (estado.propios[seccion].includes(campo)) {
        const quitar = el("button", "quitar", "enlace quitar");
        quitar.type = "button";
        quitar.addEventListener("click", () => {
          estado.propios[seccion] = estado.propios[seccion].filter((c) => c !== campo);
          dibujarListas();
        });
        li.append(quitar);
      }
      if (campo.etiquetas && campo.etiquetas.length) label.title = "Etiquetas: " + campo.etiquetas.join(", ");
      lista.append(li);
    }
  }
  actualizarBotones();
}

function marcarSeccion(seccion, valor) {
  for (const campo of campos(seccion)) campo.marcado = valor;
  dibujarListas();
}

function elegirArchivo(archivo) {
  if (!archivo) return;
  estado.archivo = archivo;
  $("zona-texto").textContent = archivo.name + " (" + Math.max(1, Math.round(archivo.size / 1024)) + " KB)";
  $("zona-documento").classList.add("con-archivo");
  actualizarBotones();
}

function armarSolicitud() {
  const convertir = (seccion) => campos(seccion).filter((c) => c.marcado).map((c) => {
    const campo = { nombre: c.nombre, etiquetas: c.etiquetas || [], tipo: c.tipo };
    if (c.descripcion) campo.descripcion = c.descripcion;
    return campo;
  });
  return { camposCabecera: convertir("cabecera"), camposDetalle: convertir("detalle") };
}

function hayCamposMarcados() {
  const s = armarSolicitud();
  return s.camposCabecera.length + s.camposDetalle.length > 0;
}

function actualizarBotones() {
  const enCurso = estado.controlador !== null;
  $("extraer").disabled = enCurso || !estado.archivo || !hayCamposMarcados();
  $("cancelar").hidden = !enCurso;
}

function agregarCampoPropio(evento) {
  evento.preventDefault();
  const form = $("form-propio");
  const datos = new FormData(form);
  const nombre = String(datos.get("nombre") || "").trim();
  const seccion = datos.get("seccion") === "detalle" ? "detalle" : "cabecera";
  const tipo = TIPOS.includes(datos.get("tipo")) ? datos.get("tipo") : "TEXTO";
  const error = $("error-propio");
  if (!NOMBRE_VALIDO.test(nombre)) {
    error.textContent = "Nombre inválido: letras, números y guión bajo, comenzando con letra (hasta 64).";
    return;
  }
  if (campos(seccion).some((c) => c.nombre === nombre)) {
    error.textContent = "Ya existe un campo \"" + nombre + "\" en " + seccion + ".";
    return;
  }
  const etiquetas = String(datos.get("etiquetas") || "").split(",").map((e) => e.trim()).filter((e) => e);
  const descripcion = String(datos.get("descripcion") || "").trim();
  estado.propios[seccion].push({ nombre, tipo, etiquetas, descripcion: descripcion || undefined, marcado: true });
  error.textContent = "";
  form.reset();
  form.hidden = true;
  dibujarListas();
}

async function copiarSolicitud() {
  const texto = JSON.stringify(armarSolicitud(), null, 2);
  const manual = $("solicitud-manual");
  try {
    if (!navigator.clipboard) throw new Error("sin portapapeles");
    await navigator.clipboard.writeText(texto);
    manual.hidden = true;
    $("estado").textContent = "Solicitud copiada.";
  } catch (e) {
    manual.value = texto;
    manual.hidden = false;
    manual.focus();
    manual.select();
    $("estado").textContent = "Copiá la solicitud del recuadro.";
  }
}

/* ---------- Extracción ---------- */

async function extraer() {
  if (estado.controlador || !estado.archivo || !hayCamposMarcados()) return;
  const cuerpo = new FormData();
  cuerpo.append("documento", estado.archivo, estado.archivo.name);
  cuerpo.append("solicitud", new Blob([JSON.stringify(armarSolicitud())], { type: "application/json" }));

  estado.controlador = new AbortController();
  const inicio = Date.now();
  $("resultado").classList.add("atenuado");
  $("estado").textContent = "Extrayendo… 0 s";
  estado.reloj = setInterval(() => {
    $("estado").textContent = "Extrayendo… " + Math.round((Date.now() - inicio) / 1000) + " s";
  }, 1000);
  actualizarBotones();

  try {
    const r = await fetch(ENDPOINT, { method: "POST", body: cuerpo, signal: estado.controlador.signal });
    if (r.ok) {
      mostrarResultado(await r.json());
      $("estado").textContent = "Listo en " + ((Date.now() - inicio) / 1000).toFixed(1) + " s.";
    } else {
      let problema = null;
      if ((r.headers.get("content-type") || "").includes("json")) {
        try { problema = await r.json(); } catch (e) { problema = null; }
      }
      mostrarError(r.status, problema, r.headers.get("Retry-After"));
      $("estado").textContent = "";
    }
  } catch (e) {
    if (e.name === "AbortError") {
      $("estado").textContent = "Cancelado (el servidor puede seguir procesando la solicitud).";
    } else {
      mostrarError(0, null, null);
      $("estado").textContent = "";
    }
  } finally {
    clearInterval(estado.reloj);
    estado.reloj = null;
    estado.controlador = null;
    $("resultado").classList.remove("atenuado");
    actualizarBotones();
  }
}

function cancelar() {
  if (estado.controlador) estado.controlador.abort();
}

/* ---------- Panel derecho ---------- */

function insignia(fuente) {
  if (fuente === "QR_ARCA") return el("span", "QR", "insignia qr");
  if (fuente === "DOCUMENTO") return el("span", "DOC", "insignia doc");
  return document.createTextNode("–");
}

function textoValor(valor) {
  return valor === null || valor === undefined ? "No encontrado" : String(valor);
}

function tabla(encabezados) {
  const t = el("table");
  const fila = el("tr");
  for (const h of encabezados) fila.append(el("th", h));
  t.append(el("thead"));
  t.tHead.append(fila);
  t.append(el("tbody"));
  return t;
}

function dibujarCabecera(cabecera) {
  const destino = $("pestana-cabecera");
  const entradas = Object.entries(cabecera || {});
  if (!entradas.length) {
    destino.replaceChildren(el("p", "Sin campos de cabecera.", "vacio"));
    return;
  }
  const t = tabla(["Campo", "Valor", "Fuente", "Descripción"]);
  for (const [nombre, campo] of entradas) {
    const c = campo || {};
    const fila = el("tr");
    const valor = el("td", textoValor(c.valor), c.valor === null || c.valor === undefined ? "no-encontrado" : "");
    const fuente = el("td");
    fuente.append(insignia(c.fuente));
    fila.append(el("td", nombre), valor, fuente, el("td", c.descripcion));
    t.tBodies[0].append(fila);
  }
  destino.replaceChildren(t);
}

function dibujarDetalle(detalle) {
  const destino = $("pestana-detalle");
  const filas = Array.isArray(detalle) ? detalle : [];
  if (!filas.length) {
    destino.replaceChildren(el("p", "Sin filas.", "vacio"));
    return;
  }
  const columnas = [];
  for (const fila of filas) {
    for (const nombre of Object.keys(fila || {})) if (!columnas.includes(nombre)) columnas.push(nombre);
  }
  const t = tabla(["#"].concat(columnas));
  filas.forEach((fila, i) => {
    const tr = el("tr");
    tr.append(el("td", i + 1));
    for (const nombre of columnas) {
      const c = (fila || {})[nombre] || {};
      const vacio = c.valor === null || c.valor === undefined;
      const clases = [vacio ? "no-encontrado" : "", c.descripcion ? "con-nota" : ""].join(" ").trim();
      const td = el("td", textoValor(c.valor), clases);
      if (c.descripcion) td.title = c.descripcion;
      tr.append(td);
    }
    t.tBodies[0].append(tr);
  });
  destino.replaceChildren(t);
}

function dibujarTiempos(respuesta) {
  const m = respuesta.metadatos || {};
  const tiempos = m.tiemposMs || {};
  const t = tabla(["Etapa", "ms"]);
  for (const clave of ["deteccion", "qr", "extraccionTexto", "rasterizacion", "llm", "esperaCola", "total"]) {
    const fila = el("tr");
    fila.append(el("td", clave), el("td", tiempos[clave] ?? "–"));
    t.tBodies[0].append(fila);
  }
  const extra = tabla(["Dato", "Valor"]);
  const qr = respuesta.qrArca || {};
  for (const [nombre, valor] of [
    ["tipoDocumento", m.tipoDocumento], ["modoProcesamiento", m.modoProcesamiento], ["paginas", m.paginas],
    ["modelo", m.modelo], ["verificacionTextual", m.verificacionTextual], ["qrArca", qr.descripcion],
  ]) {
    const fila = el("tr");
    fila.append(el("td", nombre), el("td", valor ?? "–"));
    extra.tBodies[0].append(fila);
  }
  $("pestana-tiempos").replaceChildren(t, el("p"), extra);
}

function mostrarResultado(respuesta) {
  const r = respuesta || {};
  const m = r.metadatos || {};
  const total = (m.tiemposMs || {}).total;
  const partes = ["HTTP 200"];
  if (total !== undefined) partes.push("total " + total + " ms");
  if (m.modoProcesamiento) partes.push("modo " + m.modoProcesamiento);
  if (m.modelo) partes.push("modelo " + m.modelo);
  if (m.paginas !== undefined) partes.push(m.paginas + " página(s)");
  partes.push("QR " + (r.qrArca && r.qrArca.presente ? "sí" : "no"));
  $("resumen").textContent = partes.join(" · ");
  $("error").hidden = true;
  dibujarCabecera(r.cabecera);
  dibujarDetalle(r.detalle);
  $("json").textContent = JSON.stringify(r, null, 2);
  dibujarTiempos(r);
}

function mostrarError(status, problema, retryAfter) {
  const caja = $("error");
  const titulo = status === 0
    ? "No se pudo conectar con el servicio"
    : (problema && problema.title ? "HTTP " + status + " · " + problema.title : "Error HTTP " + status);
  const hijos = [el("strong", titulo)];
  if (problema && problema.detail) hijos.push(el("span", problema.detail));
  if (retryAfter) hijos.push(el("p", "Reintentar en " + retryAfter + " s."));
  caja.replaceChildren(...hijos);
  caja.hidden = false;
  $("resumen").textContent = status === 0 ? "Sin respuesta." : "La solicitud terminó con error " + status + ".";
}

function elegirPestana(nombre) {
  for (const boton of document.querySelectorAll("[data-pestana]")) {
    const activa = boton.dataset.pestana === nombre;
    boton.setAttribute("aria-selected", String(activa));
    $("pestana-" + boton.dataset.pestana).hidden = !activa;
  }
}

/* ---------- Inicio ---------- */

function iniciar() {
  const zona = $("zona-documento");
  const entrada = $("archivo");
  entrada.addEventListener("change", () => elegirArchivo(entrada.files[0]));
  zona.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); entrada.click(); } });
  zona.addEventListener("dragover", (e) => { e.preventDefault(); zona.classList.add("arrastrando"); });
  zona.addEventListener("dragleave", () => zona.classList.remove("arrastrando"));
  zona.addEventListener("drop", (e) => {
    e.preventDefault();
    zona.classList.remove("arrastrando");
    elegirArchivo(e.dataTransfer.files[0]);
  });

  for (const boton of document.querySelectorAll("[data-marcar]")) {
    boton.addEventListener("click", () => marcarSeccion(boton.dataset.marcar, boton.dataset.valor === "true"));
  }
  $("abrir-propio").addEventListener("click", () => { $("form-propio").hidden = false; });
  $("cerrar-propio").addEventListener("click", () => {
    $("form-propio").hidden = true;
    $("error-propio").textContent = "";
  });
  $("form-propio").addEventListener("submit", agregarCampoPropio);
  $("extraer").addEventListener("click", extraer);
  $("cancelar").addEventListener("click", cancelar);
  $("copiar").addEventListener("click", copiarSolicitud);
  for (const boton of document.querySelectorAll("[data-pestana]")) {
    boton.addEventListener("click", () => elegirPestana(boton.dataset.pestana));
  }
  dibujarListas();
}

document.addEventListener("DOMContentLoaded", iniciar);
