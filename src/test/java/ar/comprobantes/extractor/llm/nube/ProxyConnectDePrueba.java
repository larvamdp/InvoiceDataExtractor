package ar.comprobantes.extractor.llm.nube;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Proxy CONNECT mínimo: 407 sin Proxy-Authorization esperado; con él, abre el túnel hacia localhost:destino. */
public final class ProxyConnectDePrueba implements AutoCloseable {
    public final AtomicInteger connectAutenticados = new AtomicInteger();
    /** CONNECT que traían algún Proxy-Authorization, correcto o no. */
    public final AtomicInteger connectConCredenciales = new AtomicInteger();
    private final ServerSocket socket;
    private final String esperado;
    private final int destino;

    public ProxyConnectDePrueba(String esperado, int destino) throws IOException {
        this.socket = new ServerSocket(0);
        this.esperado = esperado;
        this.destino = destino;
        Thread t = new Thread(this::aceptar, "proxy-connect-prueba");
        t.setDaemon(true);
        t.start();
    }

    public int puerto() {
        return socket.getLocalPort();
    }

    private void aceptar() {
        try {
            while (!socket.isClosed()) {
                Socket cliente = socket.accept();
                Thread t = new Thread(() -> atender(cliente));
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException ignorada) {
            // socket cerrado
        }
    }

    private void atender(Socket cliente) {
        try (cliente) {
            InputStream in = cliente.getInputStream();
            OutputStream out = cliente.getOutputStream();
            while (true) {
                String cabecera = leerCabecera(in);
                if (cabecera == null) {
                    return;
                }
                if (!cabecera.startsWith("CONNECT ")) {
                    return;
                }
                if (cabecera.toLowerCase().contains("proxy-authorization:")) {
                    connectConCredenciales.incrementAndGet();
                }
                if (!cabecera.toLowerCase().contains(("Proxy-Authorization: " + esperado).toLowerCase())) {
                    out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                        + "Proxy-Authenticate: Basic realm=\"p\"\r\nContent-Length: 0\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    continue;
                }
                connectAutenticados.incrementAndGet();
                try (Socket destinoSocket = new Socket("localhost", destino)) {
                    out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread hacia = new Thread(() -> copiar(in, destinoSocketOut(destinoSocket)));
                    hacia.setDaemon(true);
                    hacia.start();
                    copiar(destinoSocketIn(destinoSocket), out);
                }
                return;
            }
        } catch (IOException ignorada) {
            // conexión cerrada
        }
    }

    private static OutputStream destinoSocketOut(Socket s) {
        try {
            return s.getOutputStream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InputStream destinoSocketIn(Socket s) {
        try {
            return s.getInputStream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copiar(InputStream in, OutputStream out) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignorada) {
            // fin del túnel
        }
    }

    /** Lee hasta CRLFCRLF byte a byte para no consumir bytes del túnel. */
    private static String leerCabecera(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            bos.write(b);
            byte[] a = bos.toByteArray();
            int n = a.length;
            if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') {
                return bos.toString(StandardCharsets.US_ASCII);
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
