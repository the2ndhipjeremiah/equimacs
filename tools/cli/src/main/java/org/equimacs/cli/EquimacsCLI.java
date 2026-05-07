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

        try {
            Request request = parseCommand(cli);
            System.out.println(sendRequest(request));
            if (request instanceof Request.Reload) awaitBridgeReady();
        } catch (CliParseException e) {
            exitWithError(e.getMessage());
        } catch (Exception e) {
            exitWithError(e.getMessage());
        }
    }

    public static Request parseCommand(CliArgs cli) {
        List<String> args = cli.positional();
        String cmd = args.get(0).toLowerCase();
        try {
            return switch (cmd) {
                case "bp" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: bp <file>:<line>");
                    String spec = args.get(1);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 0) throw new CliParseException("Usage: bp <file>:<line> (missing line number)");
                    yield new Request.SetBreakpoint(
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)),
                        cli.getOption("condition", "c"));
                }
                case "list", "bps" -> new Request.ListBreakpoints();
                case "clear" -> new Request.ClearAllBreakpoints();
                case "resume" -> new Request.Resume();
                case "suspend" -> new Request.Suspend();
                case "step" -> {
                    Request.StepType type = Request.StepType.OVER;
                    if (args.size() > 1) type = Request.StepType.valueOf(args.get(1).toUpperCase());
                    yield new Request.Step(type);
                }
                case "gogo" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: gogo <command...>");
                    yield new Request.GogoExec(String.join(" ", args.subList(1, args.size())));
                }
                case "reload" -> new Request.Reload();
                case "workspace" -> new Request.GetWorkspace();
                case "problems" -> new Request.GetProblems(
                    args.size() > 1 ? args.get(1) : null,
                    cli.getOption("severity", "s"));
                case "build" -> new Request.Build(
                    args.size() > 1 ? args.get(1) : null,
                    cli.getOption("kind", "k"));
                case "classpath" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: classpath <project>");
                    yield new Request.GetClasspath(args.get(1));
                }
                case "describe" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: describe <project>");
                    yield new Request.GetProjectDescription(args.get(1));
                }
                case "refresh" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: refresh <project>");
                    yield new Request.RefreshProject(args.get(1));
                }
                case "refactor-prepare" -> {
                    if (args.size() < 3 || !args.get(1).equals("rename-symbol")) {
                        throw new CliParseException(
                            "Usage: refactor-prepare rename-symbol <file>:<offset> --to <newName>");
                    }
                    String newName = cli.getOption("to");
                    if (newName == null || newName.isBlank()) {
                        throw new CliParseException(
                            "Usage: refactor-prepare rename-symbol <file>:<offset> --to <newName>");
                    }
                    String spec = args.get(2);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 0) {
                        throw new CliParseException(
                            "Usage: refactor-prepare rename-symbol <file>:<offset> --to <newName>");
                    }
                    yield new Request.PrepareRenameSymbol(
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)),
                        newName);
                }
                case "refactor-apply" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: refactor-apply <refactoringId>");
                    yield new Request.ApplyPreparedRefactoring(args.get(1));
                }
                case "refactor-abort" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: refactor-abort <refactoringId>");
                    yield new Request.AbortPreparedRefactoring(args.get(1));
                }
                case "refactor-status" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: refactor-status <refactoringId>");
                    yield new Request.GetPreparedRefactoring(args.get(1));
                }
                case "quickfixes" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: quickfixes <file>:<line>");
                    String spec = args.get(1);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 0) throw new CliParseException("Usage: quickfixes <file>:<line>");
                    yield new Request.GetQuickFixes(
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)));
                }
                case "applyfix" -> {
                    if (args.size() < 3) throw new CliParseException("Usage: applyfix <file>:<line> <index>");
                    String spec = args.get(1);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 0) throw new CliParseException("Usage: applyfix <file>:<line> <index>");
                    yield new Request.ApplyFix(
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)),
                        Integer.parseInt(args.get(2)));
                }
                case "threads" -> new Request.GetThreads();
                case "stack" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: stack <threadId>");
                    yield new Request.GetStack(Long.parseLong(args.get(1)));
                }
                case "vars" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: vars <frameId>");
                    yield new Request.GetVariables(Long.parseLong(args.get(1)));
                }
                case "wait-event" -> {
                    String t = cli.getOption("timeout", "t");
                    yield new Request.WaitEvent(t != null ? Integer.parseInt(t) : 30_000);
                }
                case "launch" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: launch <config-name>");
                    yield new Request.Launch(args.get(1));
                }
                case "list-launches" -> new Request.ListLaunches();
                case "sessions" -> new Request.ListSessions();
                case "terminate" -> new Request.Terminate();
                case "shutdown" -> new Request.Shutdown();
                case "review-add" -> {
                    if (args.size() < 3) throw new CliParseException("Usage: review-add <file>:<line> <text...> [--author <name>]");
                    String spec = args.get(1);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 0) throw new CliParseException("Usage: review-add <file>:<line> <text...>");
                    String author = cli.getOption("author", "a");
                    if (author == null) author = "user";
                    String text = String.join(" ", args.subList(2, args.size()));
                    yield new Request.AddReviewComment(
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)),
                        author, text);
                }
                case "review-list" -> new Request.ListReviewComments(args.size() > 1 ? args.get(1) : null);
                case "review-reply" -> {
                    if (args.size() < 3) throw new CliParseException("Usage: review-reply <commentId> <text...> [--author <name>]");
                    String author = cli.getOption("author", "a");
                    if (author == null) author = "user";
                    String text = String.join(" ", args.subList(2, args.size()));
                    yield new Request.ReplyToComment(args.get(1), author, text);
                }
                case "review-resolve" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: review-resolve <commentId>");
                    yield new Request.ResolveComment(args.get(1));
                }
                case "review-diagnostics" -> new Request.ReviewDiagnostics(args.size() > 1 ? args.get(1) : null);
                case "eclipse-workbench" -> new Request.EclipseWorkbench();
                case "eclipse-editor-diagnostics" -> new Request.EclipseEditorDiagnostics();
                case "review-editor-diagnostics" -> {
                    if (args.size() < 2) {
                        yield new Request.ReviewEditorDiagnostics(null, -1);
                    }
                    String spec = args.get(1);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 0) {
                        yield new Request.ReviewEditorDiagnostics(spec, -1);
                    }
                    yield new Request.ReviewEditorDiagnostics(
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)));
                }
                default -> {
                    throw new CliParseException("Unknown command: " + cmd);
                }
            };
        } catch (CliParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CliParseException("Parsing error: " + e.getMessage(), e);
        }
    }

    public static String sendRequest(Request request) throws Exception {
        return sendRequest(request, resolveSocketPath());
    }

    public static String sendRequest(Request request, Path socketPath) throws Exception {
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

    private static Path resolveSocketPath() {
        String env = System.getenv("EQUIMACS_SOCKET");
        if (env != null && !env.isBlank()) return Path.of(env);
        String prop = System.getProperty("equimacs.socket");
        if (prop != null && !prop.isBlank()) return Path.of(prop);
        return Path.of(System.getProperty("user.home"), ".equimacs.sock");
    }

    private static void awaitBridgeReady() {
        Path socketPath = resolveSocketPath();
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
