package org.equimacs.debug;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchListener;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.IStreamListener;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;

/**
 * Captures stdout/stderr streams from launched Eclipse debug/run processes into {@link ConsoleOutputStore}
 * without consuming or altering the Eclipse Console view monitors.
 */
final class ConsoleCaptureService implements ILaunchListener, IDebugEventSetListener {

    private record ProcessListeners(
        IStreamMonitor stdoutMonitor,
        IStreamListener stdoutListener,
        IStreamMonitor stderrMonitor,
        IStreamListener stderrListener
    ) {}

    private final ConsoleOutputStore store = new ConsoleOutputStore();
    private final Object lock = new Object();
    private final Map<IProcess, ProcessListeners> attachedProcesses = new IdentityHashMap<>();
    private volatile boolean disposed;

    ConsoleCaptureService() {
        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        if (debugPlugin != null) {
            debugPlugin.addDebugEventListener(this);
            ILaunchManager launchManager = debugPlugin.getLaunchManager();
            if (launchManager != null) {
                launchManager.addLaunchListener(this);
                scanLaunches(launchManager.getLaunches());
            }
        }
    }

    Map<String, Object> getConsoleOutput(String launchName, String stream, int limit) {
        ConsoleOutputStore.StreamFilter filter = ConsoleOutputStore.parseStreamFilter(stream);
        scanExistingLaunches();
        ConsoleOutputStore.ConsolePage page = store.snapshot(launchName, filter, limit);
        return ConsoleOutputStore.toPageMap(page);
    }

    String clearConsoleOutput() {
        store.clear();
        return "console cleared";
    }

    void dispose() {
        disposed = true;
        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        if (debugPlugin != null) {
            debugPlugin.removeDebugEventListener(this);
            ILaunchManager launchManager = debugPlugin.getLaunchManager();
            if (launchManager != null) {
                launchManager.removeLaunchListener(this);
            }
        }
        List<ProcessListeners> listeners;
        synchronized (lock) {
            listeners = new ArrayList<>(attachedProcesses.values());
            attachedProcesses.clear();
        }
        for (ProcessListeners entry : listeners) {
            detachListeners(entry);
        }
    }

    @Override
    public void launchAdded(ILaunch launch) {
        attachLaunch(launch);
    }

    @Override
    public void launchChanged(ILaunch launch) {
        attachLaunch(launch);
    }

    @Override
    public void launchRemoved(ILaunch launch) {
        if (launch == null) {
            return;
        }
        List<ProcessListeners> removed = new ArrayList<>();
        synchronized (lock) {
            for (IProcess process : launch.getProcesses()) {
                ProcessListeners pl = attachedProcesses.remove(process);
                if (pl != null) {
                    removed.add(pl);
                }
            }
        }
        for (ProcessListeners pl : removed) {
            detachListeners(pl);
        }
    }

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        if (events == null || disposed) {
            return;
        }
        for (DebugEvent event : events) {
            if (event.getKind() == DebugEvent.CREATE && event.getSource() instanceof IProcess process) {
                attachProcess(process);
            }
        }
    }

    private void scanExistingLaunches() {
        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        if (debugPlugin == null) {
            return;
        }
        ILaunchManager launchManager = debugPlugin.getLaunchManager();
        if (launchManager != null) {
            scanLaunches(launchManager.getLaunches());
        }
    }

    private void scanLaunches(ILaunch[] launches) {
        if (launches == null) {
            return;
        }
        for (ILaunch launch : launches) {
            attachLaunch(launch);
        }
    }

    private void attachLaunch(ILaunch launch) {
        if (launch == null || disposed) {
            return;
        }
        for (IProcess process : launch.getProcesses()) {
            attachProcess(process);
        }
    }

    private void attachProcess(IProcess process) {
        if (process == null) {
            return;
        }
        synchronized (lock) {
            if (disposed || attachedProcesses.containsKey(process)) {
                return;
            }
            IStreamsProxy proxy = process.getStreamsProxy();
            if (proxy == null) {
                return;
            }
            String launchName = resolveLaunchName(process);
            String processLabel = resolveProcessLabel(process);
            IStreamMonitor outMonitor = proxy.getOutputStreamMonitor();
            IStreamListener outListener = attachMonitor(
                outMonitor, launchName, processLabel, ConsoleOutputStore.StreamKind.STDOUT);
            IStreamMonitor errMonitor = proxy.getErrorStreamMonitor();
            IStreamListener errListener = attachMonitor(
                errMonitor, launchName, processLabel, ConsoleOutputStore.StreamKind.STDERR);
            if (outListener == null && errListener == null) {
                return;
            }
            attachedProcesses.put(process, new ProcessListeners(outMonitor, outListener, errMonitor, errListener));
        }
    }

    private IStreamListener attachMonitor(
        IStreamMonitor monitor,
        String launchName,
        String processLabel,
        ConsoleOutputStore.StreamKind streamKind
    ) {
        if (monitor == null) {
            return null;
        }
        IStreamListener listener = new IStreamListener() {
            @Override
            public void streamAppended(String text, IStreamMonitor source) {
                if (!disposed) {
                    store.append(System.currentTimeMillis(), launchName, processLabel, streamKind, text);
                }
            }
        };
        // Read existing contents and register listener atomically before releasing monitor so chunks stay ordered.
        synchronized (monitor) {
            String existing = monitor.getContents();
            if (existing != null && !existing.isEmpty()) {
                store.append(System.currentTimeMillis(), launchName, processLabel, streamKind, existing);
            }
            monitor.addListener(listener);
        }
        return listener;
    }

    private static void detachListeners(ProcessListeners entry) {
        if (entry.stdoutMonitor() != null && entry.stdoutListener() != null) {
            entry.stdoutMonitor().removeListener(entry.stdoutListener());
        }
        if (entry.stderrMonitor() != null && entry.stderrListener() != null) {
            entry.stderrMonitor().removeListener(entry.stderrListener());
        }
    }

    private static String resolveLaunchName(IProcess process) {
        ILaunch launch = process.getLaunch();
        if (launch != null) {
            ILaunchConfiguration config = launch.getLaunchConfiguration();
            if (config != null && config.getName() != null) {
                return config.getName();
            }
        }
        return resolveProcessLabel(process);
    }

    private static String resolveProcessLabel(IProcess process) {
        String label = process.getLabel();
        return label != null ? label : "";
    }
}
