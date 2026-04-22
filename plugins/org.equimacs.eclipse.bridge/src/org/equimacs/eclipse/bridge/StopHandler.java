package org.equimacs.eclipse.bridge;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

public class StopHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Activator activator = Activator.getDefault();
        if (activator != null) {
            activator.stopServer();
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Equimacs", "Equimacs bridge stopped.");
        }
        return null;
    }
}
