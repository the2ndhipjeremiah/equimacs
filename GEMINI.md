# Equimacs: Gemini Redirect

Refer to `.context/GEMINI.md` for specific Gemini instructions.
Refer to `.context/AGENTS.md` for essential workspace paths and project vision.

## Workflows

### Manager CLI
The primary tool for development tasks (agent-optimized JSON output):
```powershell
java -cp "lib/gson.jar" tools/mgr/src/main/java/org/equimacs/mgr/EquimacsMgr.java <command>
```
Commands: `build`, `deploy`, `test-cli`, `sync-context`, `list-context`, `clean`, `all`.
Supports `discovery` or `--schema` for machine-readable interface description.

### Context Exploration
Agents must use `list-context` to see all files in the private context (JSON output):
```powershell
java -cp "lib/gson.jar" tools/mgr/src/main/java/org/equimacs/mgr/EquimacsMgr.java list-context
```

