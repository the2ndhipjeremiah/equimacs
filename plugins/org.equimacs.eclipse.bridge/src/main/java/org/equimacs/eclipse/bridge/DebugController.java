package org.equimacs.eclipse.bridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
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
        // Fallback: not in a Java project or JDT model unavailable — type name may be wrong
        String name = resource.getName();
        String simpleName = name.substring(0, name.lastIndexOf('.'));
        Activator.logError("Could not resolve fully-qualified type name for " + resource.getFullPath() + ", falling back to '" + simpleName + "'. Ensure the file is in an Eclipse Java project.", null);
        return simpleName;
    }

    public String executeGogo(String command, BundleContext ctx) throws Exception {
        ServiceReference<CommandProcessor> ref = ctx.getServiceReference(CommandProcessor.class);
        if (ref == null) throw new Exception("Gogo CommandProcessor service not found — is the OSGi console enabled? (add -console to eclipse.ini)");
        CommandProcessor processor = ctx.getService(ref);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(baos, true, "UTF-8");
            try (CommandSession session = processor.createSession(InputStream.nullInputStream(), out, out)) {
                Object result = session.execute(command);
                out.flush();
                String output = baos.toString("UTF-8").trim();
                if (result != null) {
                    String rs = result.toString();
                    if (!rs.isEmpty()) output = output.isEmpty() ? rs : output + "\n" + rs;
                }
                return output.isEmpty() ? "(no output)" : output;
            }
        } finally {
            ctx.ungetService(ref);
        }
    }

    public List<Map<String, Object>> listBreakpoints() throws CoreException {
        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        IBreakpoint[] breakpoints = manager.getBreakpoints();
        List<Map<String, Object>> result = new ArrayList<>();
        for (IBreakpoint bp : breakpoints) {
            Map<String, Object> info = new HashMap<>();
            IResource resource = bp.getMarker().getResource();
            info.put("resource", resource.getFullPath().toString());
            info.put("enabled", bp.isEnabled());
            if (bp instanceof ILineBreakpoint lineBp) {
                info.put("line", lineBp.getLineNumber());
            }
            if (bp instanceof IJavaLineBreakpoint javaBp) {
                info.put("typeName", javaBp.getTypeName());
                String cond = javaBp.getCondition();
                if (cond != null && !cond.isBlank()) info.put("condition", cond);
            }
            result.add(info);
        }
        return result;
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
