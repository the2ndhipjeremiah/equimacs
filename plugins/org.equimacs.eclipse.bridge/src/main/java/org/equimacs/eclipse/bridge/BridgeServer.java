package org.equimacs.eclipse.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.equimacs.protocol.Request;
import org.equimacs.protocol.Response;

public final class BridgeServer {
    private final Gson gson = BridgeProtocol.createGson();
    private final Function<Request, Response> dispatcher;
    private ExecutorService serverExecutor = Executors.newCachedThreadPool();
    private volatile ServerSocketChannel serverChannel;
    private volatile Path socketPath;

    public BridgeServer(Function<Request, Response> dispatcher) {
        this.dispatcher = dispatcher;
    }

    public synchronized void start() throws IOException {
        if (isRunning()) {
            Activator.logInfo("BridgeServer.start skipped: already running on " + socketPath);
            Activator.traceLifecycle("BridgeServer.start skipped: already running on " + socketPath);
            return;
        }

        serverExecutor = Executors.newCachedThreadPool();
        socketPath = Path.of(System.getProperty("user.home"), ".equimacs.sock");
        Activator.logInfo("BridgeServer.start preparing socket at " + socketPath);
        Activator.traceLifecycle("BridgeServer.start user.home=" + System.getProperty("user.home"));
        Activator.traceLifecycle("BridgeServer.start socketPath=" + socketPath);
        boolean deleted = Files.deleteIfExists(socketPath);
        Activator.logInfo("BridgeServer.start deleteIfExists(" + socketPath + ")=" + deleted);
        Activator.traceLifecycle("BridgeServer.start deleteIfExists=" + deleted);

        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath));

        Activator.logInfo("Equimacs Listening on Unix Socket: " + socketPath);
        Activator.traceLifecycle("BridgeServer.start bound=" + socketPath);
        serverExecutor.submit(this::acceptLoop);
    }

    public synchronized void stop() throws IOException {
        IOException failure = null;
        Activator.logInfo("BridgeServer.stop requested for " + socketPath);
        Activator.traceLifecycle("BridgeServer.stop requested for " + socketPath);
        if (serverChannel != null) {
            try {
                serverChannel.close();
                Activator.traceLifecycle("BridgeServer.stop closed channel");
            } catch (IOException e) {
                failure = e;
            } finally {
                serverChannel = null;
            }
        }
        if (socketPath != null) {
            try {
                boolean deleted = Files.deleteIfExists(socketPath);
                Activator.traceLifecycle("BridgeServer.stop deleteIfExists=" + deleted);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        shutdownExecutor();
        if (failure != null) {
            throw failure;
        }
    }

    public boolean isRunning() {
        ServerSocketChannel channel = serverChannel;
        return channel != null && channel.isOpen();
    }

    private void acceptLoop() {
        try {
            Activator.traceLifecycle("BridgeServer.acceptLoop started");
            while (isRunning()) {
                ServerSocketChannel channel = serverChannel;
                if (channel == null) {
                    break;
                }
                SocketChannel clientChannel = channel.accept();
                serverExecutor.submit(() -> handleClient(clientChannel));
            }
        } catch (IOException e) {
            if (isRunning()) {
                Activator.logError("Equimacs Server Error", e);
                Activator.traceLifecycle("BridgeServer.acceptLoop ioerror=" + e);
            }
        } catch (Exception e) {
            Activator.logError("Equimacs Server Fatal Error", e);
            Activator.traceLifecycle("BridgeServer.acceptLoop fatal=" + e);
        } finally {
            Activator.logInfo("Equimacs Server Thread Stopped.");
            Activator.traceLifecycle("BridgeServer.acceptLoop stopped");
        }
    }

    private void handleClient(SocketChannel clientChannel) {
        try (clientChannel;
             BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(clientChannel)));
             PrintWriter out = new PrintWriter(Channels.newOutputStream(clientChannel), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                out.println(serializeResponse(processLine(line)));
            }
        } catch (IOException e) {
            Activator.logError("Client connection error", e);
        }
    }

    private Response processLine(String line) {
        try {
            Activator.traceInbound(line);
            return dispatcher.apply(gson.fromJson(line, Request.class));
        } catch (JsonParseException e) {
            return new Response.Error("Invalid JSON: " + e.getMessage(), null);
        } catch (Exception e) {
            return new Response.Error("Execution Error: " + e.getMessage(), null);
        }
    }

    private String serializeResponse(Response response) {
        String json = gson.toJson(response);
        Activator.traceOutbound(json);
        return json;
    }

    private void shutdownExecutor() {
        serverExecutor.shutdown();
        try {
            if (!serverExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                serverExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            serverExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
