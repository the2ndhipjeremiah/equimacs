# Equimacs

> **Status: experimental / unstable.** APIs, command names, and the wire protocol may change without notice. Not ready for production use.

Equimacs is a local bridge between Eclipse and external tools. It consists of:

- an Eclipse plugin that listens on `~/.equimacs.sock` (default)
- `eqmd`, a headless launcher that runs the bridge inside Eclipse with no GUI (defaults to `~/.equimacs-headless/equimacs.sock`, can run alongside the IDE)
- `eqm-cli`, a CLI for sending bridge requests (set `EQUIMACS_SOCKET` to target a non-default daemon)
- `eqm-mgr`, a CLI for local build, packaging, deploy, and repo maintenance tasks

The current implementation is centered on Java debugging and Eclipse workspace automation. CDT support is not implemented yet.

## `eqm-cli`

`eqm-cli` sends one request to the running bridge and prints one JSON response.

Current commands:

```text
eqm-cli bp <file>:<line> [-c <condition>]
eqm-cli list
eqm-cli clear
eqm-cli resume
eqm-cli suspend
eqm-cli step [over|into|return]
eqm-cli threads
eqm-cli stack <threadId>
eqm-cli vars <frameId>
eqm-cli reload
eqm-cli gogo <command...>
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
eqm-cli shutdown
eqm-cli --schema
```

`shutdown` is only handled by the headless daemon. In the IDE bridge it should
fail with no registered handler, so the CLI cannot accidentally close the GUI
Eclipse process.

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

This compiles the protocol, bridge/debug/app bundles, CLI tools, packages
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

This builds only the CLI library, protocol, CLI, and parser tests. It does not
start Eclipse and should stay fast.

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
