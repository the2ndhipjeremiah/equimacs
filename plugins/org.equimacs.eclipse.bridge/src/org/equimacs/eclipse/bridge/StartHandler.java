package org.equimacs.eclipse.bridge;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

public class StartHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Activator activator = Activator.getDefault();
        if (activator != null) {
            activator.startServer();
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Equimacs", "Equimacs bridge started on port 12345.");
        } else {
            MessageDialog.openError(HandlerUtil.getActiveShell(event), "Equimacs", "Failed to get Equimacs Activator.");
        }
        return null;
    }
}
