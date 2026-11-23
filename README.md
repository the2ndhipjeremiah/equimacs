# Equimacs

> **Status: experimental / unstable.** APIs, command names, and the wire protocol may change without notice. Not ready for production use.

Equimacs is a local bridge between Eclipse and external tools. It consists of:

- an Eclipse plugin that listens on `~/.equimacs.sock` (default)
- `eqmd`, a headless launcher that runs the bridge inside Eclipse with no GUI (defaults to `~/.equimacs-headless/equimacs.sock`, can run alongside the IDE)
- `eqm-cli`, a CLI for sending bridge requests (set `EQUIMACS_SOCKET` to target a non-default daemon)
- `eqm-mgr`, a CLI for local build, packaging, deploy, and repo maintenance tasks

Equimacs is focused on Java (JDT) debugging and Eclipse workspace automation.

## Install

Prerequisites:

- JDK 26
- Eclipse with RCP and JDT installed
- A local clone of this repository

Create a local `.env` from the example and point it at your Eclipse and JDK
installations:

```powershell
Copy-Item .env.example .env
```

```bash
cp .env.example .env
```

Edit `.env`:

```text
ECLIPSE_HOME=<eclipse-home>
JAVA_HOME=<jdk-home>
```

Build and deploy Equimacs:

```powershell
java Build.java
```

```bash
java Build.java
```

The build compiles the bundles and CLI tools, then deploys the Eclipse bundles
into `<eclipse-home>/dropins/`. Restart Eclipse after the first deploy so the
dropins reconciler can load the bundles.

Add the packaged CLI directories to your shell `PATH`, or call the tools by
their full paths:

```powershell
$env:PATH = "$PWD\tools\cli\build\app\eqm-cli;$PWD\tools\mgr\build\app\eqm-mgr;$env:PATH"
```

```bash
export PATH="$PWD/tools/cli/build/app/eqm-cli:$PWD/tools/mgr/build/app/eqm-mgr:$PATH"
```

Verify the IDE bridge after Eclipse starts:

```powershell
eqm-cli handlers
```

```bash
eqm-cli handlers
```

To run without the Eclipse UI, start the headless daemon:

```powershell
tools\eqmd\eqmd.cmd
```

```bash
tools/eqmd/eqmd
```

In another shell, point `eqm-cli` at the daemon socket:

```powershell
$env:EQUIMACS_SOCKET="$env:USERPROFILE\.equimacs-headless\equimacs.sock"
eqm-cli handlers
```

```bash
export EQUIMACS_SOCKET="$HOME/.equimacs-headless/equimacs.sock"
eqm-cli handlers
```

## `eqm-cli`

`eqm-cli` sends one request to the running bridge and prints one JSON response.

Current commands:

```text
eqm-cli bp <file>:<line> [-c <condition>]
eqm-cli logpoint <file>:<line> [message...] [-m <template>] [-c <condition>] [--no-vars]
eqm-cli trace [--limit <n>]
eqm-cli trace-clear
eqm-cli list
eqm-cli clear
eqm-cli resume
eqm-cli suspend
eqm-cli step [over|into|return]
eqm-cli threads
eqm-cli stack <threadId>
eqm-cli vars <frameId>
eqm-cli eval <frameId> <expression...>
eqm-cli reload
eqm-cli gogo <command...>
eqm-cli handlers
eqm-cli workspace
eqm-cli problems [project] [-s error|warning|info|all]
eqm-cli build [project] [-k full|incremental|clean|auto]
eqm-cli classpath <project>
eqm-cli describe <project>
eqm-cli refresh <project>
eqm-cli refactor-prepare rename-symbol <file>:<offset> --to <newName>
eqm-cli refactor-apply <refactoringId>
eqm-cli refactor-abort <refactoringId>
eqm-cli refactor-status <refactoringId>
eqm-cli quickfixes <file>:<line>
eqm-cli applyfix <file>:<line> <index>
eqm-cli wait-event [--timeout <ms>]
eqm-cli launch <config-name>
eqm-cli list-launches
eqm-cli sessions
eqm-cli terminate
eqm-cli review-add <file>:<line> <text...> [--author <name>]
eqm-cli review-list [file] [--changed-files | --changed-hunks | --filter all|changed-files|changed-hunks]
eqm-cli review-reply <commentId> <text...> [--author <name>]
eqm-cli review-resolve <commentId>
eqm-cli review-diagnostics [file]
eqm-cli eclipse-workbench
eqm-cli eclipse-editor-diagnostics
eqm-cli eclipse-command <commandId> [<file>:<offset>] [--params <json>]
eqm-cli show-view <viewId> [secondaryId]
eqm-cli ui-tree [--root active|all|dialog|editor|view:<viewId>] [--depth <n>] [--all] [--filter <text>] [--type <widgetType>]
eqm-cli ui-click [widgetId] [--id <widgetId>] [--root <scope>] [--type <widgetType>] [--text <label>] [--index <n>] [--item <itemText>] [--double-click]
eqm-cli ui-type <widgetId> <value...> [--append] [--submit]
eqm-cli view-open <viewId> [specJson] [--title <title>] [--spec <json>] [--spec-file <path>]
eqm-cli view-update <viewId> [specJson] [--title <title>] [--spec <json>] [--spec-file <path>]
eqm-cli view-close <viewId>
eqm-cli view-list
eqm-cli inspect-frame <frameId> [--open true|false | --no-open] [--view-id <id>]
eqm-cli inspect-value <frameId> <expression...> [--expr <expr>] [--depth <n>] [--open true|false | --no-open] [--view-id <id>]
eqm-cli inspect-symbol <file>:<offset> [--open true|false | --no-open] [--view-id <id>]
eqm-cli inspect-providers
eqm-cli call-explorer <file>:<offset> [--direction callers|callees|both] [--depth <n>] [--hide-jdk true|false | --show-jdk] [--mode tree|hybrid] [--view-id <id>]
eqm-cli trace-view [--limit <n>] [--live true|false | --no-live] [--view-id <id>]
eqm-cli junit-view [runId] [--failures | --failures-only true|false] [--live true|false | --no-live] [--view-id <id>]
eqm-cli review-editor-diagnostics [file[:line]]
eqm-cli custom <target> <action> [argsJson | --args <json>]
eqm-cli find-refs <file>:<offset>
eqm-cli call-hierarchy <file>:<offset> [--direction callers|callees] [--depth <n>]
eqm-cli type-hierarchy <file>:<offset>
eqm-cli open-file <file>[:<line>[-<endLine>]]
eqm-cli run-tests <project> [class[#method]] [--mode run|debug] [--runner junit5|junit6|junit4|junit3] [--wait true|false] [--no-wait] [--timeout <ms>]
eqm-cli test-results [runId] [--failures]
eqm-cli console [launchName] [--launch <name>] [--stream all|stdout|stderr] [--limit <n>]
eqm-cli console-clear
eqm-cli shutdown
eqm-cli --schema
```

`shutdown` is only handled by the headless daemon. In the IDE bridge it should
fail with no registered handler, so the CLI cannot accidentally close the GUI
Eclipse process.

### Common Workflows

**1. Non-blocking execution tracing (`logpoint` / `trace`)**

Trace values across iterations without modifying source files, rebuilding, or pausing the JVM:

```powershell
eqm-cli logpoint /myproject/src/OrderService.java:42 "order={orderId}, total={price * qty}" -c "qty > 1"
eqm-cli launch MyApp
eqm-cli wait-event            # emits LogpointHit events without suspending execution
eqm-cli trace --limit 20      # inspect the most recent buffered entries (ring buffer holds 500)
eqm-cli trace-clear
```

- Pass `--vars false` (or trailing `--no-vars`) to omit local variable snapshots on hot paths.
- Logpoints coexist with regular breakpoints: place conditional `logpoint` probes upstream and a `bp` downstream to capture the trail leading into a failure before suspending.
- `eqm-cli bps` reports `"kind": "logpoint"` vs `"breakpoint"`; `eqm-cli clear` removes both.

**2. Suspended frame inspection & evaluation (`eval`)**

```powershell
eqm-cli bp /myproject/src/OrderService.java:58
eqm-cli launch MyApp
eqm-cli wait-event            # note threadId from BreakpointHit
eqm-cli threads
eqm-cli stack <threadId>      # note frameId
eqm-cli vars <frameId>
eqm-cli eval <frameId> "items.stream().filter(i -> i.qty() > 0).count()"
```

**3. Static JDT navigation & IDE jump**

```powershell
eqm-cli find-refs /myproject/src/OrderService.java:412
eqm-cli call-hierarchy /myproject/src/OrderService.java:412 --direction callers --depth 3
eqm-cli type-hierarchy /myproject/src/OrderService.java:85
eqm-cli open-file /myproject/src/OrderService.java:40-55
```

**4. External OSGi bundle extensions (`custom`)**

Third-party OSGi bundles deployed into `<eclipse-home>/dropins/` can register an `IBridgeCommandHandler` with service property `equimacs.commands=custom:<target>` and handle requests without modifying Equimacs core:

```powershell
eqm-cli custom my-ext inspect --args '{"scope":"workspace"}'
```

**5. Headless JUnit execution & failure inspection (`run-tests` / `test-results`)**

```powershell
eqm-cli run-tests myproject com.example.OrderServiceTest#calculatesDiscount
eqm-cli test-results --failures          # inspect failed/errored tests + parsed stack frames
eqm-cli run-tests myproject com.example.OrderServiceTest --mode debug --no-wait
```

**6. Moldable Inspector & Native Toolkit Views (`inspect-*`, `call-explorer`, `trace-view`, `junit-view`, `view-open`)**

Open contextual multi-tab inspectors on suspended debug frames, expressions/JSON strings, or JDT symbols, and explore call graphs, execution traces, and test failures in native SWT views:

```powershell
eqm-cli inspect-frame <frameId>
eqm-cli inspect-value <frameId> "order" --depth 3
eqm-cli inspect-symbol /myproject/src/OrderService.java:412
eqm-cli call-explorer /myproject/src/OrderService.java:412 --direction both --depth 3 --mode hybrid
eqm-cli trace-view --limit 200
eqm-cli junit-view --failures
```

- External OSGi bundles can register `IMoldableViewProvider` services to contribute custom inspector tabs without SWT dependencies.
- `view-open` / `view-update` support native Java/SWT kinds (`table`, `tree`, `markdown`, `key-value`, `tabs`, `graph`, `split`, `form`), optional `live` bridge-event bindings, and an optional `html` WebView2 view host.
- See [docs/moldable-toolkit.md](docs/moldable-toolkit.md) for full `ViewSpec` JSON schemas, `ViewAction` types, `live` bindings, `window.equimacs` JS bridge docs, and `IMoldableViewProvider` SPI examples.

**7. Live UI Inspection, Widget Driving & Diff-Filtered Reviews (`ui-*`, `review-list`)**

Inspect live SWT workbench controls or modal dialogs, drive clicks/text input, filter review threads to current git diffs, or open custom native views:

```powershell
eqm-cli ui-tree --root active --depth 6
eqm-cli ui-type w_12 "calculate" --submit
eqm-cli ui-click --type Button --text "OK"
eqm-cli review-list --changed-hunks
eqm-cli view-open my-notes '{"kind":"markdown","title":"Notes","content":"# Hello"}'
```

## `eqm-mgr`

`eqm-mgr` is the local manager CLI for this repository.

Current commands:

```text
eqm-mgr build
eqm-mgr deploy
eqm-mgr test-cli <cmd> [args...]
eqm-mgr sync-context
eqm-mgr list-context
eqm-mgr diagnose
eqm-mgr logs
eqm-mgr clean
eqm-mgr all
eqm-mgr package
eqm-mgr reproduce
```

## Build

This repo uses `Build.java` rather than Gradle.

Environment is read from `.env`:

- `JAVA_HOME`: JDK 26
- `ECLIPSE_HOME`: Eclipse installation root

Typical local build:

```powershell
java Build.java
```

This compiles the protocol, bridge/debug/ui/app bundles, CLI tools, packages
`eqm-cli` and `eqm-mgr`, and deploys the bundles into the configured Eclipse
installation.

Packaged app images are written under:

```text
tools/cli/build/app/eqm-cli/
tools/mgr/build/app/eqm-mgr/
```

## Tests

Parser/unit tests:

```powershell
java Build.java --test
```

This runs fast pure-Java unit tests without starting Eclipse: CLI parser tests
(`tools/cli/src/test/java/`), debug core tests (`plugins/org.equimacs.debug/src/test/java/`),
and UI/view-host/toolkit/review core tests (`plugins/org.equimacs.eclipse.ui/src/test/java/`).

Headless end-to-end tests:

```powershell
java Build.java --e2e
```

This performs a full build/deploy, starts `eqmd` with an isolated workspace and
socket under `tests/e2e/workspaces/`, drives the bridge through typed protocol
requests, then shuts the daemon down through the headless-only `shutdown`
command.

Maintenance rules:

- Add or update a parser test in `tools/cli/src/test/java/` whenever CLI argv
  parsing changes.
- Add or update an E2E test in `tests/e2e/src/test/java/` whenever bridge
  behavior changes.
- E2E tests should call `EquimacsCLI.sendRequest(Request, Path)` through
  `EqmdRpc`; do not shell out to `eqm-cli.exe` for normal bridge behavior.
- Keep daemon workspaces isolated. Use `EqmdHarness` instead of the default
  `~/.equimacs.sock` or `~/.equimacs-headless/` paths.
- Generated E2E workspaces are temporary and should not be committed.

## Eclipse

After building and deploying the plugin into `dropins/`, the bridge starts automatically when Eclipse loads the bundle.

Use `Equimacs Bridge > Start Listening` only if you have stopped it and want to bring the socket back manually.

The IDE build also includes `org.equimacs.eclipse.ui`, which contributes:

- **Equimacs Review view** (`org.equimacs.eclipse.ui.reviewView`), right-click/keybinding
  support (`M1+M3+C`) for adding review comments, automatic line re-anchoring on save,
  git-diff filtering (`--changed-files` / `--changed-hunks`), and persistent workspace markers
  under `.equimacs-review/`
- **Native-first `ModdableView` host** (`org.equimacs.eclipse.ui.moddableView`), built-in
  Moldable Toolkit views (`call-explorer`, `trace-view`, `junit-view`), and auto-expiring
  `open-file` range highlights (`org.equimacs.eclipse.ui.tempHighlight`)
- **Live SWT UI inspection & driving** (`ui-tree`, `ui-click`, `ui-type`)

Note that `eqm-cli reload` hot-reloads `org.equimacs.eclipse.bridge` and `org.equimacs.debug`,
whereas changes to `org.equimacs.eclipse.ui` require restarting Eclipse so the workbench
extension registry refreshes cleanly.

Useful diagnostics:

```powershell
eqm-cli handlers
eqm-cli view-list
eqm-cli inspect-providers
eqm-cli review-diagnostics /myproject/src/myproject/Main.java
eqm-cli eclipse-editor-diagnostics
eqm-cli review-editor-diagnostics /myproject/src/myproject/Main.java:42
```

`handlers` is bridge-native and reports which OSGi command handler owns each
request type. It should show both `org.equimacs.debug` and
`org.equimacs.eclipse.ui` handlers as `usedByBridge: true` in the IDE.

## Headless Daemon

Run the bridge without opening the Eclipse UI:

```powershell
tools\eqmd\eqmd.cmd
```

By default the daemon uses:

```text
%USERPROFILE%\.equimacs-headless\workspace
%USERPROFILE%\.equimacs-headless\equimacs.sock
```

Override with `EQUIMACS_HOME`, `EQUIMACS_WORKSPACE`, and `EQUIMACS_SOCKET`.
Point the CLI at a daemon socket with:

```powershell
$env:EQUIMACS_SOCKET="$env:USERPROFILE\.equimacs-headless\equimacs.sock"
eqm-cli bps
eqm-cli shutdown
```
