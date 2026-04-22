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

public class DebugController {

    public void setBreakpoint(String filePath, int lineNumber, String condition) throws CoreException {
        IResource resource = resolveResource(filePath);
        if (resource == null) {
            Activator.logError("Resource not found in workspace: " + filePath, null);
            throw new CoreException(new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR, Activator.PLUGIN_ID, "Resource not found: " + filePath));
        }

        String extension = resource.getFileExtension();
        if ("java".equalsIgnoreCase(extension)) {
            String typeName = resolveJavaTypeName(resource);
            org.eclipse.jdt.debug.core.IJavaLineBreakpoint bp = JDIDebugModel.createLineBreakpoint(resource, typeName, lineNumber, -1, -1, 0, true, null);
            if (condition != null && !condition.isBlank()) {
                bp.setCondition(condition);
                bp.setConditionEnabled(true);
                Activator.logInfo("Java Conditional Breakpoint Set: " + typeName + ":" + lineNumber + " if (" + condition + ")");
            } else {
                Activator.logInfo("Java Breakpoint Set: " + typeName + ":" + lineNumber);
            }
        } else {
            throw new CoreException(new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR, Activator.PLUGIN_ID, "Unsupported file type (C/C++ cut for now): " + extension));
        }
    }

    private IResource resolveResource(String pathStr) {
        Path path = new Path(pathStr);
        // 1. Try as workspace-relative path
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(path);
        if (resource != null) return resource;

        // 2. Try as absolute path (mapped to workspace)
        IResource[] resources = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(java.net.URI.create("file:///" + pathStr.replace("\\", "/")));
        if (resources.length > 0) return resources[0];

        return null;
    }

    private String resolveJavaTypeName(IResource resource) {
        org.eclipse.jdt.core.IJavaElement element = org.eclipse.jdt.core.JavaCore.create(resource);
        if (element instanceof org.eclipse.jdt.core.ICompilationUnit cu) {
            org.eclipse.jdt.core.IType[] types;
            try {
                types = cu.getAllTypes();
                if (types.length > 0) {
                    return types[0].getFullyQualifiedName();
                }
            } catch (org.eclipse.jdt.core.JavaModelException ignored) {}
        }
        // Fallback: use filename as type (less reliable)
        String name = resource.getName();
        return name.substring(0, name.lastIndexOf('.'));
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

    @FunctionalInterface interface TargetAction { void run(IDebugTarget target) throws CoreException; }
    @FunctionalInterface interface ThreadAction { void run(IThread thread) throws CoreException; }
}
