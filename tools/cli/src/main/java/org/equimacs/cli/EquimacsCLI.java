package org.equimacs.cli;

import com.google.gson.Gson;
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
    private static final Gson gson = new Gson();

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
            Response response = sendRequest(request);
            System.out.println(gson.toJson(response));
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
                    String[] parts = args.get(1).split(":");
                    yield new Request.SetBreakpoint(parts[0], Integer.parseInt(parts[1]), condition);
                }
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

    private static Response sendRequest(Request request) throws Exception {
        Path socketPath = Path.of(System.getProperty("user.home"), ".equimacs.sock");
        
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            
            PrintWriter out = new PrintWriter(Channels.newOutputStream(channel), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)));
            
            out.println(gson.toJson(request));
            String line = in.readLine();
            if (line == null) throw new Exception("No response from server");
            
            return gson.fromJson(line, Response.class);
        }
    }

    private static void exitWithError(String message) {
        System.out.println(gson.toJson(new Response.Error(message, null)));
        System.exit(1);
    }
}
