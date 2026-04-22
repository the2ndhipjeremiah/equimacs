package org.equimacs.eclipse.bridge;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.osgi.framework.Bundle;

public class StartHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            // Ensure the bundle is started (transiently if needed)
            Bundle bundle = Platform.getBundle(Activator.PLUGIN_ID);
            if (bundle != null && bundle.getState() != Bundle.ACTIVE) {
                bundle.start(Bundle.START_TRANSIENT);
            }

            Activator activator = Activator.getDefault();
            if (activator != null) {
                boolean wasRunning = activator.isServerRunning();
                if (wasRunning) {
                    activator.stopServer();
                }

                activator.startServer();
                String userHome = System.getProperty("user.home");
                String socketPath = userHome + "/.equimacs.sock";
                if (activator.isServerRunning()) {
                    MessageDialog.openInformation(
                        HandlerUtil.getActiveShell(event),
                        "Equimacs Bridge",
                        (wasRunning
                            ? "Equimacs bridge restarted and is listening on Unix socket: "
                            : "Equimacs bridge started listening on Unix socket: ")
                            + socketPath
                            + "\n" + Activator.buildBanner()
                    );
                } else {
                    MessageDialog.openError(
                        HandlerUtil.getActiveShell(event),
                        "Equimacs Bridge Error",
                        "Equimacs bridge did not start listening on Unix socket: " + socketPath
                            + "\n" + Activator.buildBanner()
                            + "\nCheck the Eclipse Error Log for the underlying failure."
                    );
                }
            } else {
                MessageDialog.openError(
                    HandlerUtil.getActiveShell(event),
                    "Equimacs Bridge Error",
                    "Failed to get Equimacs Activator after bundle start."
                );
            }
        } catch (Exception e) {
            Activator.logError("Failed to start Equimacs Bridge", e);
            throw new ExecutionException("Failed to start Equimacs Bridge", e);
        }
        return null;
    }
}
