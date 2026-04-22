# Equimacs: The Agentic Eclipse Bridge

Equimacs is an agent-centric bridge for the Eclipse IDE, providing programmatic control over debugging, breakpoints, and hot-reloading for Java and C/C++.

## Setup
1. Add -console, 1234, and -Dosgi.console.enable.builtin=true to your eclipse.ini.
2. Place org.equimacs.eclipse.bridge in your dropins.
3. Click Equimacs > Start Listening.

## Commands (Port 12345)
- set-breakpoint:/path/to/file:type_or_handle:line
- clear-all

## Development & Reproduction

The project uses a custom "Boring Java Build" (no Gradle). 

### Prerequisites
- JDK 25+
- Eclipse installation (for plugin APIs)
- `.env` file configured with `JAVA_HOME` and `ECLIPSE_HOME` (see `.env.example`)

### Reproduce Full Build (Clean -> Build -> Package)
To perform a deterministic, zero-to-exe reproduction:
```powershell
java -cp "lib/gson.jar" tools/mgr/src/main/java/org/equimacs/mgr/EquimacsMgr.java reproduce
```

The resulting standalone executable will be at `tools/cli/build/app/equimacs/equimacs.exe`.
