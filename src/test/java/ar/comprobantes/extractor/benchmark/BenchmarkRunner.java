package ar.comprobantes.extractor.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Mide tiempos y precisión del servicio contra el dataset. Se ejecuta con exec:java (ver README). */
public final class BenchmarkRunner {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final Duration TIMEOUT_HTTP = Duration.ofMinutes(15);
    static final String ENCABEZADO = "documento,forma,modo,qrPresente,status,totalMs,deteccionMs,qrMs,"
            + "extraccionTextoMs,rasterizacionMs,llmMs,esperaColaMs,"
            + "aciertos,camposTotales,inventados,omitidos,cumple60s";

    record Fila(String documento, String forma, String modo, String qr, int status, long totalMs, long deteccionMs,
                long qrMs, long extraccionTextoMs, long rasterizacionMs, long llmMs, long esperaColaMs, int aciertos, int camposTotales, int inventados, int omitidos,
                boolean cumple60s) {
        String csv() {
            return String.join(",", documento, forma, modo, qr, String.valueOf(status), String.valueOf(totalMs),
                    String.valueOf(deteccionMs), String.valueOf(qrMs), String.valueOf(extraccionTextoMs),
                    String.valueOf(rasterizacionMs), String.valueOf(llmMs), String.valueOf(esperaColaMs), String.valueOf(aciertos),
                    String.valueOf(camposTotales), String.valueOf(inventados), String.valueOf(omitidos),
                    String.valueOf(cumple60s));
        }
    }

    private BenchmarkRunner() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opc = new java.util.HashMap<>(Map.of("--url", "http://localhost:8080",
                "--dataset", "benchmark/dataset", "--concurrencia", "1", "--salida", "benchmark/resultados",
                "--filtro", "", "--modelo", ""));
        for (int i = 0; i + 1 < args.length; i += 2) {
            opc.put(args[i], args[i + 1]);
        }
        String url = opc.get("--url");
        Path dataset = Path.of(opc.get("--dataset"));
        int concurrencia = Integer.parseInt(opc.get("--concurrencia"));
        Path salida = Path.of(opc.get("--salida"));
        String filtro = opc.get("--filtro");

        List<Path> docs;
        try (Stream<Path> s = Files.list(dataset)) {
            docs = s.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains(filtro))
                    .sorted().toList();
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        if (!opc.get("--modelo").isBlank()) {
            modeloRespuesta.set(opc.get("--modelo"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(concurrencia);
        List<Fila> filas = new ArrayList<>();
        try {
            List<Future<Fila>> futuros = new ArrayList<>();
            for (Path d : docs) {
                futuros.add(pool.submit(() -> procesarSeguro(http, url, d)));
            }
            for (Future<Fila> f : futuros) {
                filas.add(f.get());
            }
        } finally {
            pool.shutdown();
        }

        String modelo = modeloRespuesta.get() != null ? modeloRespuesta.get() : "sin-respuestas-200";
        Files.createDirectories(salida);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path csv = salida.resolve(ts + "-" + modelo.replace(':', '_').replace('/', '_') + ".csv");
        List<String> lineas = new ArrayList<>();
        lineas.add("# modelo=" + modelo + " concurrencia=" + concurrencia + " filtro=" + filtro);
        lineas.add(ENCABEZADO);
        filas.forEach(f -> lineas.add(f.csv()));
        Files.write(csv, lineas, StandardCharsets.UTF_8);
        resumen(filas, modelo, concurrencia);
        System.out.println("CSV: " + csv);
    }

    /** Modelo indicado con --modelo o, si no, el de la primera respuesta 200. */
    private static final AtomicReference<String> modeloRespuesta = new AtomicReference<>();

    private static Fila procesarSeguro(HttpClient http, String url, Path dir) {
        try {
            return procesar(http, url, dir);
        } catch (IOException e) {
            String id = dir.getFileName().toString();
            System.out.println("<- " + id + " ERROR al leer el dataset: " + e.getClass().getSimpleName());
            return new Fila(id, id.substring(id.lastIndexOf('-') + 1), "-", "-", -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }
    }

    @SuppressWarnings("unchecked")
    private static Fila procesar(HttpClient http, String url, Path dir) throws IOException {
        String id = dir.getFileName().toString();
        String forma = id.substring(id.lastIndexOf('-') + 1);
        Path doc = Files.exists(dir.resolve("documento.pdf")) ? dir.resolve("documento.pdf") : dir.resolve("documento.jpg");
        String tipo = doc.toString().endsWith(".pdf") ? "application/pdf" : "image/jpeg";
        byte[] solicitud = Files.readAllBytes(dir.resolve("solicitud.json"));
        Map<String, Object> esperado = OM.readValue(dir.resolve("esperado.json").toFile(),
                new TypeReference<Map<String, Object>>() {});

        String limite = "----bench" + System.nanoTime();
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        parte(cuerpo, limite, "documento", doc.getFileName().toString(), tipo, Files.readAllBytes(doc));
        parte(cuerpo, limite, "solicitud", "solicitud.json", "application/json", solicitud);
        cuerpo.write(("--" + limite + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/api/v1/extracciones/sync"))
                .timeout(TIMEOUT_HTTP)
                .header("Content-Type", "multipart/form-data; boundary=" + limite)
                .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray())).build();
        long t0 = System.nanoTime();
        System.out.println("-> " + id);
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long medido = (System.nanoTime() - t0) / 1_000_000;
            if (resp.statusCode() != 200) {
                Fila f = new Fila(id, forma, "-", "-", resp.statusCode(), medido, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
                System.out.println("<- " + id + " status " + resp.statusCode());
                return f;
            }
            JsonNode json = OM.readTree(resp.body());
            JsonNode meta = json.path("metadatos");
            JsonNode t = meta.path("tiemposMs");
            if (!meta.path("modelo").isMissingNode()) {
                modeloRespuesta.compareAndSet(null, meta.path("modelo").asText());
            }
            long total = t.path("total").asLong(medido);
            var r = Comparador.comparar(esperado, json);
            r.errores().forEach(e -> System.out.println("   " + id + " " + e));
            System.out.println("<- " + id + " " + total + " ms, " + r.aciertos() + "/" + r.camposTotales());
            return new Fila(id, forma, meta.path("modoProcesamiento").asText("-"),
                    String.valueOf(json.path("qrArca").path("presente").asBoolean(false)), 200, total,
                    t.path("deteccion").asLong(0), t.path("qr").asLong(0), t.path("extraccionTexto").asLong(0),
                    t.path("rasterizacion").asLong(0), t.path("llm").asLong(0), t.path("esperaCola").asLong(0), r.aciertos(), r.camposTotales(),
                    r.inventados(), r.omitidos(), total <= 60_000);
        } catch (Exception e) {
            long medido = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("<- " + id + " ERROR " + e.getClass().getSimpleName());
            return new Fila(id, forma, "-", "-", -1, medido, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }
    }

    private static void parte(ByteArrayOutputStream o, String limite, String nombre, String archivo, String tipo,
                              byte[] datos) throws IOException {
        o.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"" + nombre + "\"; filename=\""
                + archivo + "\"\r\nContent-Type: " + tipo + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        o.write(datos);
        o.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void resumen(List<Fila> filas, String modelo, int concurrencia) {
        System.out.println();
        System.out.println("Modelo: " + modelo + " | concurrencia: " + concurrencia);
        Map<String, List<Fila>> porModo = new TreeMap<>();
        for (Fila f : filas) {
            porModo.computeIfAbsent(f.status() == 200 ? f.modo() : "ERROR", k -> new ArrayList<>()).add(f);
        }
        for (var e : porModo.entrySet()) {
            System.out.println(lineaResumen(e.getKey(), e.getValue()));
        }
    }

    /** Línea de resumen de un grupo; el grupo "ERROR" no tiene métricas medidas. */
    static String lineaResumen(String clave, List<Fila> l) {
        List<Long> tiempos = l.stream().map(Fila::totalMs).sorted(Comparator.naturalOrder()).toList();
        String base = String.format(Locale.ROOT, "Modo %s: n=%d p50=%d ms p95=%d ms", clave, l.size(),
                percentil(tiempos, 50), percentil(tiempos, 95));
        int tot = l.stream().mapToInt(Fila::camposTotales).sum();
        if ("ERROR".equals(clave) || tot == 0) {
            return base + " aciertos=n/d inventados=n/d <=60s=n/d";
        }
        int ac = l.stream().mapToInt(Fila::aciertos).sum();
        int inv = l.stream().mapToInt(Fila::inventados).sum();
        long ok60 = l.stream().filter(Fila::cumple60s).count();
        return base + String.format(Locale.ROOT, " aciertos=%.1f%% inventados=%d <=60s=%.0f%%",
                100.0 * ac / tot, inv, 100.0 * ok60 / l.size());
    }

    private static long percentil(List<Long> ordenados, int p) {
        int idx = (int) Math.ceil(p / 100.0 * ordenados.size()) - 1;
        return ordenados.get(Math.max(0, idx));
    }
}
