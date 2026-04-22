# Equimacs

Equimacs is a local bridge between Eclipse and external tools. It consists of:

- an Eclipse plugin that listens on `~/.equimacs.sock`
- `eqm-cli`, a CLI for sending bridge requests
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
eqm-cli quickfixes <file>:<line>
eqm-cli applyfix <file>:<line> <index>
eqm-cli wait-event [--timeout <ms>]
eqm-cli launch <config-name>
eqm-cli list-launches
eqm-cli --schema
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
eqm-mgr clean
eqm-mgr all
eqm-mgr package
eqm-mgr reproduce
```

## Build

This repo uses [Build.java](/C:/Users/the2nd/equimacs/Build.java) rather than Gradle.

Environment is read from `.env`:

- `JAVA_HOME`: JDK 25
- `ECLIPSE_HOME`: Eclipse installation root

Typical local build:

```powershell
java Build.java
```

Packaged wrappers live in [bin/eqm-cli](/C:/Users/the2nd/equimacs/bin/eqm-cli) and [bin/eqm-mgr](/C:/Users/the2nd/equimacs/bin/eqm-mgr).

## Eclipse

After building and deploying the plugin into `dropins/`, the bridge starts automatically when Eclipse loads the bundle.

Use `Equimacs Bridge > Start Listening` only if you have stopped it and want to bring the socket back manually.
