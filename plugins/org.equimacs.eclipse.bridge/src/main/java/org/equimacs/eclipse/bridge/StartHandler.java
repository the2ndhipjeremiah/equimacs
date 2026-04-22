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
                if (activator.isServerRunning()) {
                    MessageDialog.openInformation(
                        HandlerUtil.getActiveShell(event),
                        "Equimacs Bridge",
                        "Equimacs bridge is already running."
                    );
                    return null;
                }
                
                activator.startServer();
                String userHome = System.getProperty("user.home");
                String socketPath = userHome + "/.equimacs.sock";
                MessageDialog.openInformation(
                    HandlerUtil.getActiveShell(event),
                    "Equimacs Bridge",
                    "Equimacs bridge started listening on Unix socket: " + socketPath
                );
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
