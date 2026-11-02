package com.stellar.platform.desktop.ui.graph.map;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Loopback HTTP server that serves local PMTiles with HTTP Range support.
 *
 * <p>Required because the PMTiles browser client issues Range requests. Bound to 127.0.0.1 only.
 */
public final class MapLocalAssetServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MapLocalAssetServer.class.getName());

    private final HttpServer server;
    private final Map<String, Path> packs = new ConcurrentHashMap<>();

    public MapLocalAssetServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/packs/", this::servePack);
        server.createContext("/probe/", this::serveProbeTile);
        server.setExecutor(new ThreadPoolExecutor(
                1,
                4,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "stellar-map-assets");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        ));
        server.start();
        LOG.fine(() -> "Map asset server on " + baseUrl());
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Leaflet template for the opt-in synthetic provider. Not registered as a production pack. */
    public String probeTileTemplate() {
        return baseUrl() + "/probe/{z}/{x}/{y}.png";
    }

    public String registerPack(OfflineMapPack pack) {
        Objects.requireNonNull(pack, "pack");
        packs.put(pack.id(), pack.path());
        return baseUrl() + "/packs/" + pack.id() + ".pmtiles";
    }

    public void unregisterPack(String id) {
        if (id != null) {
            packs.remove(id);
        }
    }

    private void serveProbeTile(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String rest = path.startsWith("/probe/") ? path.substring("/probe/".length()) : "";
            String[] parts = rest.split("/");
            if (parts.length != 3 || !parts[2].endsWith(".png")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            int z;
            int x;
            int y;
            try {
                z = Integer.parseInt(parts[0]);
                x = Integer.parseInt(parts[1]);
                y = Integer.parseInt(parts[2].substring(0, parts[2].length() - 4));
            } catch (NumberFormatException ex) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] png = MapSyntheticTilePng.png(z, x, y);
            Headers response = exchange.getResponseHeaders();
            response.add("Content-Type", "image/png");
            response.add("Cache-Control", "no-store");
            response.add("Access-Control-Allow-Origin", "*");
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                response.add("Content-Length", Integer.toString(png.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, png.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(png);
            }
        } finally {
            exchange.close();
        }
    }

    private void servePack(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String name = path.substring("/packs/".length());
            if (name.endsWith(".pmtiles")) {
                name = name.substring(0, name.length() - ".pmtiles".length());
            }
            Path file = packs.get(name);
            if (file == null || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            long fileLength = Files.size(file);
            Headers response = exchange.getResponseHeaders();
            response.add("Accept-Ranges", "bytes");
            response.add("Content-Type", "application/octet-stream");
            response.add("Access-Control-Allow-Origin", "*");
            response.add("Access-Control-Expose-Headers", "Accept-Ranges, Content-Range, Content-Length");

            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=")) {
                serveRange(exchange, file, fileLength, range.substring("bytes=".length()));
            } else if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                response.add("Content-Length", Long.toString(fileLength));
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, fileLength);
                try (OutputStream out = exchange.getResponseBody();
                     RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    byte[] buf = new byte[8192];
                    long remaining = fileLength;
                    while (remaining > 0) {
                        int read = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (read < 0) {
                            break;
                        }
                        out.write(buf, 0, read);
                        remaining -= read;
                    }
                }
            }
        } finally {
            exchange.close();
        }
    }

    private static void serveRange(HttpExchange exchange, Path file, long fileLength, String rangeSpec)
            throws IOException {
        String[] parts = rangeSpec.split("-", 2);
        long start = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
        long end = parts.length < 2 || parts[1].isEmpty() ? fileLength - 1 : Long.parseLong(parts[1]);
        if (start < 0 || end >= fileLength || start > end) {
            exchange.getResponseHeaders().add("Content-Range", "bytes */" + fileLength);
            exchange.sendResponseHeaders(416, -1);
            return;
        }
        long length = end - start + 1;
        Headers response = exchange.getResponseHeaders();
        response.add("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
        response.add("Content-Length", Long.toString(length));
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(206, -1);
            return;
        }
        exchange.sendResponseHeaders(206, length);
        try (OutputStream out = exchange.getResponseBody();
             RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(start);
            byte[] buf = new byte[8192];
            long remaining = length;
            while (remaining > 0) {
                int read = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buf, 0, read);
                remaining -= read;
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        packs.clear();
    }
}
