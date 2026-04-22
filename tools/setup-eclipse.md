# Equimacs Eclipse Setup

To use Equimacs, you need an Eclipse installation with the Gogo shell enabled. This allows for remote control and debugging.

## 1. Required Eclipse Features
Ensure your Eclipse installation includes:
- **Eclipse RCP** (Rich Client Platform)
- **CDT** (C/C++ Development Tools)
- **JDT** (Java Development Tools)

## 2. Enable Gogo Shell
Edit your `eclipse.ini` file (found in the Eclipse installation directory) and add the following lines at the end:

```ini
-console
1234
-Dosgi.console.enable.builtin=true
```

- `-console`: Starts the OSGi console.
- `1234`: The port for the Gogo shell (you can connect via `telnet localhost 1234`).
- `-Dosgi.console.enable.builtin=true`: Ensures the built-in console is used.

## 3. Install Equimacs Bridge
1. Export the `org.equimacs.eclipse.bridge` project as a "Deployable plug-in".
2. Place the resulting JAR in the `dropins/` folder of your Eclipse installation.
3. Restart Eclipse.

## 4. Verify Setup
Once Eclipse is running:
1. Open a terminal and run `telnet localhost 1234`.
2. You should see the `osgi>` or `g! ` prompt.
3. In Eclipse, you should see an **Equimacs** menu in the top bar.
4. Click **Equimacs > Start Listening**.
5. The bridge will now listen on port `12345` for agent commands.
