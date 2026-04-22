package org.equimacs.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import org.equimacs.protocol.Request;
import org.equimacs.protocol.Response;
import org.equimacs.protocol.ProtocolSchema;
import org.equimacs.cli.util.CliArgs;

public class EquimacsCLI {
    private static final Gson gson = new GsonBuilder()
        .registerTypeAdapter(Request.class, (JsonSerializer<Request>) (src, typeOfSrc, context) -> {
            JsonObject obj = context.serialize(src).getAsJsonObject();
            obj.addProperty("type", src.getClass().getSimpleName());
            return obj;
        })
        .create();

    public static void main(String[] args) {
        CliArgs cli = CliArgs.parse(args);

        if (cli.isDiscovery()) {
            System.out.println(ProtocolSchema.JSON_SCHEMA);
            return;
        }

        String condition = cli.getOption("condition", "c");
        Request request = parseCommand(cli.positional(), condition);
        if (request == null) return;

        try {
            System.out.println(sendRequest(request));
            if (request instanceof Request.Reload) awaitBridgeReady();
        } catch (Exception e) {
            exitWithError(e.getMessage());
        }
    }

    private static Request parseCommand(List<String> args, String condition) {
        String cmd = args.get(0).toLowerCase();
        try {
            return switch (cmd) {
                case "bp" -> {
                    if (args.size() < 2) exitWithError("Usage: bp <file>:<line>");
                    String spec = args.get(1);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 0) exitWithError("Usage: bp <file>:<line> (missing line number)");
                    yield new Request.SetBreakpoint(
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)),
                        condition);
                }
                case "list", "bps" -> new Request.ListBreakpoints();
                case "clear" -> new Request.ClearAllBreakpoints();
                case "resume" -> new Request.Resume();
                case "suspend" -> new Request.Suspend();
                case "step" -> {
                    Request.StepType type = Request.StepType.OVER;
                    if (args.size() > 1) {
                        type = Request.StepType.valueOf(args.get(1).toUpperCase());
                    }
                    yield new Request.Step(type);
                }
                case "gogo" -> {
                    if (args.size() < 2) exitWithError("Usage: gogo <command...>");
                    yield new Request.GogoExec(String.join(" ", args.subList(1, args.size())));
                }
                case "reload" -> new Request.Reload();
                case "threads" -> new Request.GetThreads();
                case "stack" -> {
                    if (args.size() < 2) exitWithError("Usage: stack <threadId>");
                    yield new Request.GetStack(Long.parseLong(args.get(1)));
                }
                case "vars" -> {
                    if (args.size() < 2) exitWithError("Usage: vars <frameId>");
                    yield new Request.GetVariables(Long.parseLong(args.get(1)));
                }
                default -> {
                    exitWithError("Unknown command: " + cmd);
                    yield null;
                }
            };
        } catch (Exception e) {
            exitWithError("Parsing error: " + e.getMessage());
            return null;
        }
    }

    private static String sendRequest(Request request) throws Exception {
        Path socketPath = Path.of(System.getProperty("user.home"), ".equimacs.sock");
        
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            
            PrintWriter out = new PrintWriter(Channels.newOutputStream(channel), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)));
            
            out.println(gson.toJson(request, Request.class));
            String line = in.readLine();
            if (line == null) throw new Exception("No response from server");
            
            return line;
        }
    }

    private static void awaitBridgeReady() {
        Path socketPath = Path.of(System.getProperty("user.home"), ".equimacs.sock");
        for (int i = 0; i < 20; i++) {
            try {
                Thread.sleep(500);
                try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                    ch.connect(UnixDomainSocketAddress.of(socketPath));
                    System.out.println(gson.toJson(new Response.Success("ready")));
                    return;
                }
            } catch (Exception ignored) {}
        }
        System.out.println(gson.toJson(new Response.Error("Bridge did not come back within 10s", null)));
        System.exit(1);
    }

    private static void exitWithError(String message) {
        System.out.println(gson.toJson(new Response.Error(message, null)));
        System.exit(1);
    }
}
