# Equimacs: The Agentic Eclipse Bridge

Equimacs is an agent-centric bridge for the Eclipse IDE, providing programmatic control over debugging, breakpoints, and hot-reloading for Java and C/C++.

## Setup
1. Add -console, 1234, and -Dosgi.console.enable.builtin=true to your eclipse.ini.
2. Place org.equimacs.eclipse.bridge in your dropins.
3. Click Equimacs > Start Listening.

## Commands (Port 12345)
- set-breakpoint:/path/to/file:type_or_handle:line
- clear-all
