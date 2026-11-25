package org.equimacs.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
                case "logpoint", "lp" -> {
                    if (args.size() < 2) {
                        throw new CliParseException(
                            "Usage: logpoint <file>:<line> [message...] [-m <template>] [-c <condition>] [--no-vars]");
                    }
                    String spec = args.get(1);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 0) {
                        throw new CliParseException("Usage: logpoint <file>:<line> (missing line number)");
                    }
                    String message = cli.getOption("message", "m");
                    if (message == null && args.size() > 2) {
                        message = String.join(" ", args.subList(2, args.size()));
                    }
                    String condition = cli.getOption("condition", "c");
                    boolean captureVars = true;
                    if (cli.hasFlag("no-vars")) {
                        captureVars = false;
                    } else {
                        String varsOpt = cli.getOption("vars");
                        if (varsOpt != null) {
                            if ("true".equalsIgnoreCase(varsOpt)) {
                                captureVars = true;
                            } else if ("false".equalsIgnoreCase(varsOpt)) {
                                captureVars = false;
                            } else {
                                throw new CliParseException(
                                    "Invalid --vars value (expected true or false): " + varsOpt);
                            }
                        }
                    }
                    yield new Request.SetLogpoint(
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)),
                        message,
                        condition,
                        captureVars);
                }
                case "trace" -> {
                    String limitStr = cli.getOption("limit", "n");
                    int limit = limitStr != null ? Integer.parseInt(limitStr) : 100;
                    yield new Request.GetTrace(limit);
                }
                case "trace-clear" -> new Request.ClearTrace();
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
                case "handlers" -> new Request.HandlerDiagnostics();
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
                case "set-classpath" -> parseSetClasspath(cli, args);
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
                case "eval", "evaluate" -> {
                    if (args.size() < 3) throw new CliParseException("Usage: eval <frameId> <expression...>");
                    yield new Request.Evaluate(
                        Long.parseLong(args.get(1)),
                        String.join(" ", args.subList(2, args.size())));
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
                case "custom" -> {
                    if (args.size() < 3) {
                        throw new CliParseException("Usage: custom <target> <action> [argsJson | --args <json>]");
                    }
                    String argsJson = cli.getOption("args");
                    if (argsJson == null && args.size() > 3) {
                        argsJson = String.join(" ", args.subList(3, args.size()));
                    }
                    yield new Request.CustomCommand(args.get(1), args.get(2), argsJson);
                }
                case "find-refs" -> {
                    if (args.size() < 2) {
                        throw new CliParseException("Usage: find-refs <file>:<offset>");
                    }
                    String spec = args.get(1);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 0) {
                        throw new CliParseException("Usage: find-refs <file>:<offset>");
                    }
                    yield new Request.FindReferences(
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)));
                }
                case "call-hierarchy" -> {
                    if (args.size() < 2) {
                        throw new CliParseException("Usage: call-hierarchy <file>:<offset> [--direction callers|callees] [--depth <n>]");
                    }
                    String spec = args.get(1);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 0) {
                        throw new CliParseException("Usage: call-hierarchy <file>:<offset>");
                    }
                    String direction = cli.getOption("direction", "d");
                    String depthStr = cli.getOption("depth");
                    int depth = depthStr != null ? Integer.parseInt(depthStr) : 1;
                    yield new Request.CallHierarchy(
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)),
                        direction != null ? direction : "callers",
                        depth);
                }
                case "type-hierarchy" -> {
                    if (args.size() < 2) {
                        throw new CliParseException("Usage: type-hierarchy <file>:<offset>");
                    }
                    String spec = args.get(1);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 0) {
                        throw new CliParseException("Usage: type-hierarchy <file>:<offset>");
                    }
                    yield new Request.TypeHierarchy(
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)));
                }
                case "open-file" -> {
                    if (args.size() < 2) {
                        throw new CliParseException("Usage: open-file <file>[:<line>[-<endLine>]]");
                    }
                    String spec = args.get(1);
                    int lastColon = spec.lastIndexOf(':');
                    // Guard against Windows drive letter like C:\foo\Bar.java
                    if (lastColon <= 1) {
                        yield new Request.OpenFile(spec, -1, -1);
                    }
                    String suffix = spec.substring(lastColon + 1);
                    String filePart = spec.substring(0, lastColon);
                    int dash = suffix.indexOf('-');
                    if (dash > 0) {
                        yield new Request.OpenFile(
                            filePart,
                            Integer.parseInt(suffix.substring(0, dash)),
                            Integer.parseInt(suffix.substring(dash + 1)));
                    }
                    int line = Integer.parseInt(suffix);
                    yield new Request.OpenFile(filePart, line, line);
                }
                case "run-tests" -> {
                    if (args.size() < 2) {
                        throw new CliParseException(
                            "Usage: run-tests <project> [class[#method]] [--mode run|debug] [--runner junit5|junit6|junit4|junit3] [--wait true|false] [--no-wait] [--timeout <ms>]");
                    }
                    String project = args.get(1);
                    String className = null;
                    String methodName = cli.getOption("method", "m");
                    if (args.size() >= 3) {
                        String target = args.get(2);
                        int hash = target.indexOf('#');
                        if (hash > 0) {
                            className = target.substring(0, hash);
                            methodName = target.substring(hash + 1);
                            if (className.isBlank() || methodName.isBlank()) {
                                throw new CliParseException("Invalid class#method target: " + target);
                            }
                        } else {
                            className = target;
                        }
                    } else {
                        className = cli.getOption("class");
                    }
                    String mode = cli.getOption("mode");
                    String runner = cli.getOption("runner");
                    Boolean wait = null;
                    if (cli.hasFlag("no-wait")) {
                        wait = Boolean.FALSE;
                    } else {
                        String waitOpt = cli.getOption("wait");
                        if (waitOpt != null) {
                            if ("true".equalsIgnoreCase(waitOpt)) {
                                wait = Boolean.TRUE;
                            } else if ("false".equalsIgnoreCase(waitOpt)) {
                                wait = Boolean.FALSE;
                            } else {
                                throw new CliParseException(
                                    "Invalid --wait value (expected true or false): " + waitOpt);
                            }
                        }
                    }
                    String timeoutStr = cli.getOption("timeout", "t");
                    int timeoutMs = timeoutStr != null ? Integer.parseInt(timeoutStr) : 60_000;
                    yield new Request.RunTests(project, className, methodName, mode, runner, wait, timeoutMs);
                }
                case "test-results" -> {
                    String runId = args.size() > 1 ? args.get(1) : null;
                    yield new Request.GetTestResults(runId, parseFailuresOnly(cli));
                }
                case "console" -> {
                    String launchName = cli.getOption("launch", "l");
                    if (launchName == null && args.size() > 1) {
                        launchName = args.get(1);
                    }
                    String streamOpt = cli.getOption("stream", "s");
                    String stream = "all";
                    if (streamOpt != null) {
                        String normalized = streamOpt.trim().toLowerCase(Locale.ROOT);
                        stream = switch (normalized) {
                            case "all" -> "all";
                            case "stdout", "out" -> "stdout";
                            case "stderr", "err" -> "stderr";
                            default -> throw new CliParseException(
                                "Invalid --stream value (expected all, stdout, or stderr): " + streamOpt);
                        };
                    }
                    String limitStr = cli.getOption("limit", "n");
                    int limit = limitStr != null ? Integer.parseInt(limitStr) : 200;
                    if (limit <= 0) {
                        throw new CliParseException("Invalid --limit value (must be > 0): " + limitStr);
                    }
                    yield new Request.GetConsoleOutput(launchName, stream, limit);
                }
                case "console-clear" -> new Request.ClearConsoleOutput();
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
                case "review-list" -> {
                    String file = args.size() > 1 ? args.get(1) : null;
                    String filter = resolveReviewFilter(cli);
                    yield new Request.ListReviewComments(file, filter);
                }
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
                case "review-remove" -> {
                    if (args.size() < 2) throw new CliParseException("Usage: review-remove <commentId>");
                    yield new Request.RemoveReviewComment(args.get(1));
                }
                case "review-diagnostics" -> new Request.ReviewDiagnostics(args.size() > 1 ? args.get(1) : null);
                case "eclipse-workbench" -> new Request.EclipseWorkbench();
                case "eclipse-editor-diagnostics" -> new Request.EclipseEditorDiagnostics();
                case "eclipse-command" -> {
                    if (args.size() < 2) {
                        throw new CliParseException("Usage: eclipse-command <commandId> [<file>:<offset>] [--params <json>]");
                    }
                    String commandId = args.get(1);
                    String paramsJson = cli.getOption("params", "p");
                    java.util.Map<String, String> params = null;
                    if (paramsJson != null && !paramsJson.isBlank()) {
                        params = gson.fromJson(
                            paramsJson,
                            new com.google.gson.reflect.TypeToken<java.util.Map<String, String>>() {}.getType());
                    }
                    if (args.size() < 3) {
                        yield new Request.EclipseCommand(commandId, null, -1, params);
                    }
                    String spec = args.get(2);
                    int lastColon = spec.lastIndexOf(':');
                    if (lastColon <= 1) {
                        yield new Request.EclipseCommand(commandId, spec, -1, params);
                    }
                    yield new Request.EclipseCommand(
                        commandId,
                        spec.substring(0, lastColon),
                        Integer.parseInt(spec.substring(lastColon + 1)),
                        params);
                }
                case "show-view" -> {
                    if (args.size() < 2) {
                        throw new CliParseException("Usage: show-view <viewId> [secondaryId]");
                    }
                    yield new Request.ShowView(args.get(1), args.size() > 2 ? args.get(2) : null);
                }
                case "ui-tree" -> parseUiTree(cli);
                case "ui-click" -> parseUiClick(cli, args);
                case "ui-type" -> parseUiType(cli, args);
                case "view-open" -> parseViewOpen(cli, args);
                case "view-update" -> parseViewUpdate(cli, args);
                case "view-close" -> {
                    if (args.size() < 2) {
                        throw new CliParseException("Usage: view-close <viewId>");
                    }
                    yield new Request.ViewClose(args.get(1));
                }
                case "view-list" -> new Request.ViewList();
                case "inspect-frame" -> parseInspectFrame(cli, args);
                case "inspect-value" -> parseInspectValue(cli, args);
                case "inspect-symbol" -> parseInspectSymbol(cli, args);
                case "inspect-providers" -> new Request.ListInspectProviders();
                case "call-explorer" -> parseCallExplorer(cli, args);
                case "trace-view" -> parseTraceView(cli);
                case "junit-view" -> parseJUnitView(cli, args);
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

    private static Request.SetClasspath parseSetClasspath(CliArgs cli, List<String> args) {
        String usage = "Usage: set-classpath <project> add <source|library|project|container|variable> <path> [--source-attachment <path>] "
            + "OR set-classpath <project> remove [<kind>] <path> [--kind <kind>]";
        if (args.size() < 4) {
            throw new CliParseException(usage);
        }
        String project = args.get(1);
        String action = parseAllowedOption("action", args.get(2), "", List.of("add", "remove"));
        String sourceAttachment = cli.getOption("source-attachment");
        if ("add".equals(action)) {
            if (args.size() < 5) {
                throw new CliParseException(usage);
            }
            String kind = parseAllowedOption(
                "kind", args.get(3), "", List.of("source", "library", "project", "container", "variable"));
            String path = args.get(4);
            return new Request.SetClasspath(project, action, kind, path, sourceAttachment);
        }
        String kind = cli.getOption("kind", "k");
        String path;
        if (args.size() >= 5) {
            kind = parseAllowedOption(
                "kind", args.get(3), "", List.of("source", "library", "project", "container", "variable"));
            path = args.get(4);
        } else {
            if (kind != null) {
                kind = parseAllowedOption(
                    "--kind", kind, "", List.of("source", "library", "project", "container", "variable"));
            }
            path = args.get(3);
        }
        return new Request.SetClasspath(project, action, kind, path, null);
    }

    private static String resolveReviewFilter(CliArgs cli) {
        boolean changedFiles = cli.hasFlag("changed-files");
        boolean changedHunks = cli.hasFlag("changed-hunks");
        String opt = cli.getOption("filter", "f");
        if (changedFiles && changedHunks) {
            throw new CliParseException("Specify only one of --changed-files or --changed-hunks");
        }
        String raw = changedFiles ? "changed-files" : (changedHunks ? "changed-hunks" : opt);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "all", "changed-files", "changed-hunks" -> normalized;
            default -> throw new CliParseException(
                "Invalid --filter value (expected all, changed-files, or changed-hunks): " + raw);
        };
    }

    private static Request.UiTree parseUiTree(CliArgs cli) {
        String root = cli.getOption("root", "r");
        if (root == null || root.isBlank()) {
            root = "active";
        }
        String depthStr = cli.getOption("depth", "d");
        int maxDepth = depthStr != null ? Integer.parseInt(depthStr) : 8;
        if (maxDepth <= 0) {
            throw new CliParseException("Invalid --depth value (must be > 0): " + depthStr);
        }
        boolean visibleOnly = true;
        if (cli.hasFlag("all")) {
            visibleOnly = false;
        } else {
            String visOpt = cli.getOption("visible-only");
            if (visOpt != null) {
                visibleOnly = parseStrictBoolean("--visible-only", visOpt);
            }
        }
        String textFilter = cli.getOption("filter", "f");
        String widgetType = cli.getOption("type", "t");
        return new Request.UiTree(root, maxDepth, visibleOnly, textFilter, widgetType);
    }

    private static Request.UiClick parseUiClick(CliArgs cli, List<String> args) {
        String widgetId = cli.getOption("id");
        if (widgetId == null && args.size() > 1) {
            widgetId = args.get(1);
        }
        String root = cli.getOption("root", "r");
        String widgetType = cli.getOption("type", "t");
        String text = cli.getOption("text");
        String itemText = cli.getOption("item");
        String indexStr = cli.getOption("index", "i");
        int index = indexStr != null ? Integer.parseInt(indexStr) : 0;
        if (index < 0) {
            throw new CliParseException("Invalid --index value (must be >= 0): " + indexStr);
        }
        boolean doubleClick = parseFlagOrBooleanOption(cli, "double-click");
        if (isBlank(widgetId) && isBlank(text) && isBlank(widgetType) && isBlank(itemText)) {
            throw new CliParseException(
                "Usage: ui-click [widgetId] [--id <widgetId>] [--root <scope>] [--type <widgetType>] [--text <label>] [--index <n>] [--item <itemText>] [--double-click]");
        }
        return new Request.UiClick(widgetId, root, widgetType, text, index, itemText, doubleClick);
    }

    private static Request.UiType parseUiType(CliArgs cli, List<String> args) {
        String usage = "Usage: ui-type <widgetId> <value...> [--append] [--submit] OR ui-type [--id <widgetId>] [--root <scope>] [--type <widgetType>] [--text <label>] [--index <n>] [--value <text>] [value...] [--append] [--submit]";
        String widgetId = cli.getOption("id");
        String root = cli.getOption("root", "r");
        String widgetType = cli.getOption("type", "t");
        String text = cli.getOption("text");
        String indexStr = cli.getOption("index", "i");
        int index = indexStr != null ? Integer.parseInt(indexStr) : 0;
        if (index < 0) {
            throw new CliParseException("Invalid --index value (must be >= 0): " + indexStr);
        }
        String value = cli.getOption("value", "v");
        boolean append = parseFlagOrBooleanOption(cli, "append");
        boolean submit = parseFlagOrBooleanOption(cli, "submit");

        if (isBlank(widgetId) && isBlank(widgetType) && isBlank(text)) {
            if (args.size() < 2) {
                throw new CliParseException(usage);
            }
            widgetId = args.get(1);
            if (value == null) {
                if (args.size() < 3) {
                    throw new CliParseException(usage);
                }
                value = String.join(" ", args.subList(2, args.size()));
            }
        } else if (value == null) {
            if (args.size() < 2) {
                throw new CliParseException(usage);
            }
            value = String.join(" ", args.subList(1, args.size()));
        }
        return new Request.UiType(widgetId, root, widgetType, text, index, value, append, submit);
    }

    private static Request.ViewOpen parseViewOpen(CliArgs cli, List<String> args) {
        ViewSpecInput input = parseViewSpecInput(
            cli, args, "Usage: view-open <viewId> [specJson] [--title <title>] [--spec <json>] [--spec-file <path>]");
        return new Request.ViewOpen(input.viewId(), input.title(), input.specJson());
    }

    private static Request.ViewUpdate parseViewUpdate(CliArgs cli, List<String> args) {
        ViewSpecInput input = parseViewSpecInput(
            cli, args, "Usage: view-update <viewId> [specJson] [--title <title>] [--spec <json>] [--spec-file <path>]");
        return new Request.ViewUpdate(input.viewId(), input.title(), input.specJson());
    }

    private record ViewSpecInput(String viewId, String title, String specJson) {}

    private static ViewSpecInput parseViewSpecInput(CliArgs cli, List<String> args, String usage) {
        if (args.size() < 2) {
            throw new CliParseException(usage);
        }
        String viewId = args.get(1);
        String title = cli.getOption("title", "t");
        String specFile = cli.getOption("spec-file");
        String specJson = cli.getOption("spec");
        if (specFile != null) {
            try {
                specJson = Files.readString(Path.of(specFile), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CliParseException("Failed to read --spec-file " + specFile + ": " + e.getMessage(), e);
            }
        } else if (specJson == null && args.size() > 2) {
            specJson = String.join(" ", args.subList(2, args.size()));
        }
        if (isBlank(specJson)) {
            throw new CliParseException(usage);
        }
        return new ViewSpecInput(viewId, title, specJson);
    }

    private static Request.InspectFrame parseInspectFrame(CliArgs cli, List<String> args) {
        if (args.size() < 2) {
            throw new CliParseException(
                "Usage: inspect-frame <frameId> [--open true|false | --no-open] [--view-id <id>]");
        }
        long frameId = Long.parseLong(args.get(1));
        Boolean openView = parseDefaultTrueToggle(cli, "open", "no-open");
        String viewId = cli.getOption("view-id");
        return new Request.InspectFrame(frameId, openView, viewId);
    }

    private static Request.InspectValue parseInspectValue(CliArgs cli, List<String> args) {
        String usage = "Usage: inspect-value <frameId> <expression...> [--depth <n>] [--open true|false | --no-open] [--view-id <id>]";
        if (args.size() < 2) {
            throw new CliParseException(usage);
        }
        long frameId = Long.parseLong(args.get(1));
        String expression = cli.getOption("expr", "e");
        if (expression == null && args.size() > 2) {
            expression = String.join(" ", args.subList(2, args.size()));
        }
        if (isBlank(expression)) {
            throw new CliParseException(usage);
        }
        int depth = parseIntAtLeast("--depth", cli.getOption("depth", "d"), 2, 1);
        Boolean openView = parseDefaultTrueToggle(cli, "open", "no-open");
        String viewId = cli.getOption("view-id");
        return new Request.InspectValue(frameId, expression, depth, openView, viewId);
    }

    private static Request.InspectSymbol parseInspectSymbol(CliArgs cli, List<String> args) {
        String usage = "Usage: inspect-symbol <file>:<offset> [--open true|false | --no-open] [--view-id <id>]";
        if (args.size() < 2) {
            throw new CliParseException(usage);
        }
        FileOffset target = parseFileOffset(args.get(1), usage);
        Boolean openView = parseDefaultTrueToggle(cli, "open", "no-open");
        String viewId = cli.getOption("view-id");
        return new Request.InspectSymbol(target.file(), target.offset(), openView, viewId);
    }

    private static Request.OpenCallExplorer parseCallExplorer(CliArgs cli, List<String> args) {
        String usage = "Usage: call-explorer <file>:<offset> [--direction callers|callees|both] [--depth <n>] [--hide-jdk true|false | --show-jdk] [--mode tree|hybrid] [--group-by none|type|package] [--max-fanout <n>] [--compare <file>:<offset>] [--view-id <id>]";
        if (args.size() < 2) {
            throw new CliParseException(usage);
        }
        FileOffset target = parseFileOffset(args.get(1), usage);
        String direction = parseAllowedOption(
            "--direction", cli.getOption("direction", "d"), "callers", List.of("callers", "callees", "both"));
        int depth = parseIntAtLeast("--depth", cli.getOption("depth"), 3, 1);
        Boolean hideJdk = parseDefaultTrueToggle(cli, "hide-jdk", "show-jdk");
        String viewMode = parseAllowedOption(
            "--mode", cli.getOption("mode", "m"), "hybrid", List.of("tree", "hybrid"));
        String viewId = cli.getOption("view-id");
        String groupByOpt = cli.getOption("group-by");
        String groupBy = groupByOpt != null
            ? parseAllowedOption("--group-by", groupByOpt, "none", List.of("none", "type", "package"))
            : null;
        int maxFanout = parseIntAtLeast("--max-fanout", cli.getOption("max-fanout"), 0, 0);
        String compareSpec = cli.getOption("compare");
        String compareFile = null;
        int compareOffset = -1;
        if (compareSpec != null && !compareSpec.isBlank()) {
            FileOffset cmp = parseFileOffset(compareSpec, usage);
            compareFile = cmp.file();
            compareOffset = cmp.offset();
        }
        return new Request.OpenCallExplorer(
            target.file(), target.offset(), direction, depth, hideJdk, viewMode, viewId,
            groupBy, maxFanout, compareFile, compareOffset);
    }

    private static Request.OpenTraceView parseTraceView(CliArgs cli) {
        int limit = parseIntAtLeast("--limit", cli.getOption("limit", "n"), 200, 1);
        Boolean live = parseDefaultTrueToggle(cli, "live", "no-live");
        String viewId = cli.getOption("view-id");
        return new Request.OpenTraceView(limit, live, viewId);
    }

    private static Request.OpenJUnitView parseJUnitView(CliArgs cli, List<String> args) {
        String runId = cli.getOption("run-id");
        if (runId == null && args.size() > 1) {
            runId = args.get(1);
        }
        Boolean failuresOnly = parseFailuresOnly(cli);
        Boolean live = parseDefaultTrueToggle(cli, "live", "no-live");
        String viewId = cli.getOption("view-id");
        return new Request.OpenJUnitView(runId, failuresOnly, live, viewId);
    }

    private record FileOffset(String file, int offset) {}

    private static FileOffset parseFileOffset(String spec, String usage) {
        int lastColon = spec.lastIndexOf(':');
        if (lastColon <= 1 || lastColon == spec.length() - 1) {
            throw new CliParseException(usage);
        }
        int offset = Integer.parseInt(spec.substring(lastColon + 1));
        if (offset < 0) {
            throw new CliParseException("Invalid offset (must be >= 0): " + offset);
        }
        return new FileOffset(spec.substring(0, lastColon), offset);
    }

    private static Boolean parseDefaultTrueToggle(CliArgs cli, String optionName, String negativeFlag) {
        if (cli.hasFlag(negativeFlag)) {
            return Boolean.FALSE;
        }
        if (cli.hasFlag(optionName)) {
            return Boolean.TRUE;
        }
        String raw = cli.getOption(optionName);
        if (raw != null) {
            return parseStrictBoolean("--" + optionName, raw);
        }
        return Boolean.TRUE;
    }

    private static boolean parseFailuresOnly(CliArgs cli) {
        boolean failuresOnly = cli.hasFlag("failures", "failures-only");
        String failuresOpt = cli.getOption("failures-only");
        if (failuresOpt != null) {
            failuresOnly = parseStrictBoolean("--failures-only", failuresOpt);
        }
        return failuresOnly;
    }

    private static int parseIntAtLeast(String flagName, String raw, int defaultVal, int minVal) {
        if (raw == null) {
            return defaultVal;
        }
        int value = Integer.parseInt(raw);
        if (value < minVal) {
            throw new CliParseException(
                "Invalid " + flagName + " value (must be >= " + minVal + "): " + raw);
        }
        return value;
    }

    private static String parseAllowedOption(String flagName, String raw, String defaultVal, List<String> allowed) {
        if (raw == null) {
            return defaultVal;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (allowed.contains(normalized)) {
            return normalized;
        }
        throw new CliParseException(
            "Invalid " + flagName + " value (expected " + String.join(", ", allowed) + "): " + raw);
    }

    private static boolean parseFlagOrBooleanOption(CliArgs cli, String name) {
        if (cli.hasFlag(name)) {
            return true;
        }
        String opt = cli.getOption(name);
        return opt != null && parseStrictBoolean("--" + name, opt);
    }

    private static boolean parseStrictBoolean(String flagName, String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new CliParseException("Invalid " + flagName + " value (expected true or false): " + raw);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
