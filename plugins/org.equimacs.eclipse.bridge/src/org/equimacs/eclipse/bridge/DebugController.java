package org.equimacs.eclipse.bridge;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.cdt.debug.core.CDebugModel;

public class DebugController {

    public void setBreakpoint(String projectPath, String typeOrSourceHandle, int lineNumber) throws CoreException {
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(new Path(projectPath));
        if (resource == null) {
            Activator.logError("Resource not found in workspace: " + projectPath, null);
            return;
        }

        String extension = resource.getFileExtension();
        if (extension == null) {
            Activator.logError("Resource has no extension: " + projectPath, null);
            return;
        }

        if ("java".equalsIgnoreCase(extension)) {
            JDIDebugModel.createLineBreakpoint(resource, typeOrSourceHandle, lineNumber, -1, -1, 0, true, null);
            Activator.logInfo("Java Breakpoint Set: " + typeOrSourceHandle + ":" + lineNumber);
        } else if (isCFile(extension)) {
            String sourceHandle = resource.getLocation().toOSString();
            CDebugModel.createLineBreakpoint(sourceHandle, resource, lineNumber, true, 0, "", true);
            Activator.logInfo("C/C++ Breakpoint Set: " + sourceHandle + ":" + lineNumber);
        } else {
            Activator.logError("Unsupported file type for breakpoint: " + extension, null);
        }
    }

    public void clearAllBreakpoints() throws CoreException {
        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        manager.removeBreakpoints(manager.getBreakpoints(), true);
        Activator.logInfo("All breakpoints cleared.");
    }

    public void resume() throws CoreException {
        executeOnActiveTarget(target -> {
            if (target.canResume()) target.resume();
        });
    }

    public void suspend() throws CoreException {
        executeOnActiveTarget(target -> {
            if (target.canSuspend()) target.suspend();
        });
    }

    public void stepOver() throws CoreException {
        executeOnFirstSuspendedThread(thread -> {
            if (thread.canStepOver()) thread.stepOver();
        });
    }

    public void stepInto() throws CoreException {
        executeOnFirstSuspendedThread(thread -> {
            if (thread.canStepInto()) thread.stepInto();
        });
    }

    public void stepReturn() throws CoreException {
        executeOnFirstSuspendedThread(thread -> {
            if (thread.canStepReturn()) thread.stepReturn();
        });
    }

    private void executeOnActiveTarget(TargetAction action) throws CoreException {
        ILaunch[] launches = DebugPlugin.getDefault().getLaunchManager().getLaunches();
        for (ILaunch launch : launches) {
            if (!launch.isTerminated()) {
                IDebugTarget target = launch.getDebugTarget();
                if (target != null && !target.isTerminated()) {
                    action.run(target);
                }
            }
        }
    }

    private void executeOnFirstSuspendedThread(ThreadAction action) throws CoreException {
        ILaunch[] launches = DebugPlugin.getDefault().getLaunchManager().getLaunches();
        for (ILaunch launch : launches) {
            IDebugTarget target = launch.getDebugTarget();
            if (target != null && !target.isTerminated()) {
                for (IThread thread : target.getThreads()) {
                    if (thread.isSuspended()) {
                        action.run(thread);
                        return; // Just act on the first one found for now
                    }
                }
            }
        }
    }

    private boolean isCFile(String extension) {
        return "c".equalsIgnoreCase(extension) || "cpp".equalsIgnoreCase(extension) || 
               "h".equalsIgnoreCase(extension) || "hpp".equalsIgnoreCase(extension) ||
               "cc".equalsIgnoreCase(extension) || "cxx".equalsIgnoreCase(extension);
    }

    @FunctionalInterface interface TargetAction { void run(IDebugTarget target) throws CoreException; }
    @FunctionalInterface interface ThreadAction { void run(IThread thread) throws CoreException; }
}
