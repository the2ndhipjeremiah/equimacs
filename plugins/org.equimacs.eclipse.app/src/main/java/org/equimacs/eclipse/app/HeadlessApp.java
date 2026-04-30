package org.equimacs.eclipse.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.equimacs.eclipse.bridge.api.IBridgeCommandHandler;
import org.equimacs.eclipse.bridge.api.IBridgeService;
import org.equimacs.protocol.Request;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.ServiceReference;
import java.util.Hashtable;

public final class HeadlessApp implements IApplication {

    private final Object shutdownLock = new Object();
    private volatile boolean stopRequested;
    private ServiceRegistration<IBridgeCommandHandler> shutdownRegistration;

    @Override
    public Object start(IApplicationContext ctx) throws Exception {
        ctx.applicationRunning();
        registerShutdownHandler();
        log("HeadlessApp.start: forcing bridge service activation");
        forceBridgeActivation();
        importWorkspaceProjects();
        log("HeadlessApp.start: bridge active, parking main thread");
        synchronized (shutdownLock) {
            while (!stopRequested) {
                shutdownLock.wait();
            }
        }
        log("HeadlessApp.start: shutdown requested, exiting");
        unregisterShutdownHandler();
        return IApplication.EXIT_OK;
    }

    @Override
    public void stop() {
        log("HeadlessApp.stop: requested");
        synchronized (shutdownLock) {
            stopRequested = true;
            shutdownLock.notifyAll();
        }
    }

    private void registerShutdownHandler() {
        BundleContext ctx = FrameworkUtil.getBundle(HeadlessApp.class).getBundleContext();
        Hashtable<String, Object> props = new Hashtable<>();
        props.put("equimacs.commands", new String[] { "Shutdown" });
        shutdownRegistration = ctx.registerService(IBridgeCommandHandler.class, req -> {
            if (!(req instanceof Request.Shutdown)) {
                throw new IllegalArgumentException("Unsupported request: " + req.getClass().getSimpleName());
            }
            requestShutdown();
            return "shutting down";
        }, props);
        log("HeadlessApp.start: registered shutdown command handler");
    }

    private void unregisterShutdownHandler() {
        if (shutdownRegistration == null) return;
        try {
            shutdownRegistration.unregister();
        } catch (IllegalStateException ignored) {
            // Already unregistered during framework shutdown.
        } finally {
            shutdownRegistration = null;
        }
    }

    private void requestShutdown() {
        synchronized (shutdownLock) {
            stopRequested = true;
            shutdownLock.notifyAll();
        }
    }

    private static void forceBridgeActivation() {
        BundleContext ctx = FrameworkUtil.getBundle(HeadlessApp.class).getBundleContext();
        ServiceReference<IBridgeService> ref = ctx.getServiceReference(IBridgeService.class);
        if (ref == null) {
            log("WARN: IBridgeService not yet registered; DS may activate it asynchronously");
            return;
        }
        IBridgeService svc = ctx.getService(ref);
        if (svc == null) {
            log("WARN: IBridgeService reference present but service unavailable");
            return;
        }
        log("Bridge service obtained: " + svc.getClass().getName());
    }

    private static void importWorkspaceProjects() {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        org.eclipse.core.runtime.IPath rootLocation = root.getLocation();
        if (rootLocation == null) return;

        Path rootPath = Path.of(rootLocation.toOSString());
        if (!Files.isDirectory(rootPath)) return;

        try (Stream<Path> stream = Files.list(rootPath)) {
            for (Path candidate : stream.filter(Files::isDirectory).toList()) {
                Path projectFile = candidate.resolve(".project");
                if (!Files.isRegularFile(projectFile)) continue;

                IProjectDescription desc = workspace.loadProjectDescription(
                    new org.eclipse.core.runtime.Path(projectFile.toString()));
                IProject project = root.getProject(desc.getName());
                if (!project.exists()) {
                    project.create(desc, new NullProgressMonitor());
                    log("Imported workspace project: " + desc.getName());
                }
                if (!project.isOpen()) {
                    project.open(new NullProgressMonitor());
                }
            }
        } catch (IOException | org.eclipse.core.runtime.CoreException e) {
            log("WARN: failed to import workspace projects: " + e.getMessage());
        }
    }

    private static void log(String message) {
        System.out.println("[equimacs-app] " + message);
    }
}
