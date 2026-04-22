# Equimacs

Equimacs is a local bridge between Eclipse and external tools. It consists of:

- an Eclipse plugin that listens on `~/.equimacs.sock`
- `eqm`, a CLI for sending bridge requests
- `eqm-mgr`, a CLI for local build, packaging, deploy, and repo maintenance tasks

The current implementation is centered on Java debugging and Eclipse workspace automation. CDT support is not implemented yet.

## `eqm`

`eqm` sends one request to the running bridge and prints one JSON response.

Current commands:

```text
eqm bp <file>:<line> [-c <condition>]
eqm list
eqm clear
eqm resume
eqm suspend
eqm step [over|into|return]
eqm threads
eqm stack <threadId>
eqm vars <frameId>
eqm reload
eqm gogo <command...>
eqm workspace
eqm problems [project] [-s error|warning|info|all]
eqm build [project] [-k full|incremental|clean|auto]
eqm classpath <project>
eqm describe <project>
eqm refresh <project>
eqm quickfixes <file>:<line>
eqm applyfix <file>:<line> <index>
eqm --schema
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

Packaged wrappers live in [bin/eqm](/C:/Users/the2nd/equimacs/bin/eqm) and [bin/eqm-mgr](/C:/Users/the2nd/equimacs/bin/eqm-mgr).

## Eclipse

After building and deploying the plugin into `dropins/`, the bridge starts automatically when Eclipse loads the bundle.

Use `Equimacs Bridge > Start Listening` only if you have stopped it and want to bring the socket back manually.
