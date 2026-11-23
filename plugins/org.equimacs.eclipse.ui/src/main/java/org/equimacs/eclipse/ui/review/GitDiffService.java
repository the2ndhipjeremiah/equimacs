package org.equimacs.eclipse.ui.review;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

/**
 * Thin Eclipse and ProcessBuilder adapter that queries git diff/status for workspace files.
 */
public final class GitDiffService {

    private static final long GIT_TIMEOUT_SECONDS = 3L;

    public GitDiffModel.GitDiffSummary summaryForWorkspaceFile(
        String workspacePath,
        Map<Path, GitDiffModel.GitDiffSummary> cache
    ) {
        Path osPath = resolveWorkspaceOsPath(workspacePath);
        if (osPath == null) {
            return unavailable(null);
        }
        Path repoRoot = findGitRoot(osPath);
        if (repoRoot == null) {
            return unavailable(null);
        }
        if (cache != null) {
            return cache.computeIfAbsent(repoRoot, this::computeSummary);
        }
        return computeSummary(repoRoot);
    }

    public boolean matches(
        ReviewComment comment,
        GitDiffModel.FilterMode mode,
        Map<Path, GitDiffModel.GitDiffSummary> cache
    ) {
        if (comment == null) {
            return false;
        }
        GitDiffModel.FilterMode effectiveMode = mode == null ? GitDiffModel.FilterMode.ALL : mode;
        if (effectiveMode == GitDiffModel.FilterMode.ALL) {
            return true;
        }
        Path osPath = resolveWorkspaceOsPath(comment.file());
        if (osPath == null) {
            return false;
        }
        Path repoRoot = findGitRoot(osPath);
        if (repoRoot == null) {
            return false;
        }
        GitDiffModel.GitDiffSummary summary = cache != null
            ? cache.computeIfAbsent(repoRoot, this::computeSummary)
            : computeSummary(repoRoot);
        return GitDiffParser.matches(
            comment,
            summary,
            effectiveMode,
            wsPath -> toRepoRelativePath(repoRoot, wsPath)
        );
    }

    public List<ReviewComment> filter(List<ReviewComment> comments, GitDiffModel.FilterMode mode) {
        if (comments == null || comments.isEmpty()) {
            return comments == null ? List.of() : comments;
        }
        GitDiffModel.FilterMode effectiveMode = mode == null ? GitDiffModel.FilterMode.ALL : mode;
        if (effectiveMode == GitDiffModel.FilterMode.ALL) {
            return comments;
        }
        Map<Path, GitDiffModel.GitDiffSummary> cache = new LinkedHashMap<>();
        List<ReviewComment> filtered = new ArrayList<>();
        for (ReviewComment comment : comments) {
            if (matches(comment, effectiveMode, cache)) {
                filtered.add(comment);
            }
        }
        return filtered;
    }

    public GitDiffModel.GitDiffSummary computeSummary(Path repoRoot) {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return unavailable(repoRoot);
        }
        GitExecResult statusResult = runGit(repoRoot, List.of("status", "--porcelain", "-uall"));
        if (statusResult.exitCode() != 0) {
            return unavailable(repoRoot);
        }

        GitExecResult diffHeadResult = runGit(repoRoot, List.of("diff", "--unified=0", "HEAD"));
        String diffOutput;
        if (diffHeadResult.exitCode() == 0) {
            diffOutput = diffHeadResult.stdout();
        } else if (!diffHeadResult.timedOut()) {
            GitExecResult unstaged = runGit(repoRoot, List.of("diff", "--unified=0"));
            GitExecResult cached = runGit(repoRoot, List.of("diff", "--cached", "--unified=0"));
            if (unstaged.exitCode() != 0 && cached.exitCode() != 0) {
                return unavailable(repoRoot);
            }
            StringBuilder combined = new StringBuilder();
            if (unstaged.exitCode() == 0 && !unstaged.stdout().isEmpty()) {
                combined.append(unstaged.stdout());
            }
            if (cached.exitCode() == 0 && !cached.stdout().isEmpty()) {
                if (combined.length() > 0 && combined.charAt(combined.length() - 1) != '\n') {
                    combined.append('\n');
                }
                combined.append(cached.stdout());
            }
            diffOutput = combined.toString();
        } else {
            return unavailable(repoRoot);
        }

        return GitDiffParser.buildSummary(repoRoot.toString(), statusResult.stdout(), diffOutput);
    }

    public static Path findGitRoot(Path startPath) {
        if (startPath == null) {
            return null;
        }
        Path current = startPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    public static Path resolveWorkspaceOsPath(String wsPath) {
        if (wsPath == null || wsPath.isBlank()) {
            return null;
        }
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            org.eclipse.core.runtime.Path eclipsePath = new org.eclipse.core.runtime.Path(wsPath);
            IResource member = root.findMember(eclipsePath);
            if (member != null) {
                Path resolved = resourceToPath(member);
                if (resolved != null) {
                    return resolved;
                }
            }
            if (eclipsePath.segmentCount() >= 2) {
                IFile candidate = root.getFile(eclipsePath);
                Path resolved = resourceToPath(candidate);
                if (resolved != null) {
                    return resolved;
                }
            }
            IPath rootLoc = root.getLocation();
            if (rootLoc != null) {
                String trimmed = wsPath.replace('\\', '/');
                while (trimmed.startsWith("/")) {
                    trimmed = trimmed.substring(1);
                }
                if (!trimmed.isEmpty()) {
                    return rootLoc.toFile().toPath().resolve(trimmed).toAbsolutePath().normalize();
                }
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    private static String toRepoRelativePath(Path repoRoot, String wsPath) {
        Path osPath = resolveWorkspaceOsPath(wsPath);
        if (osPath == null || repoRoot == null) {
            return null;
        }
        Path normRoot = repoRoot.toAbsolutePath().normalize();
        Path normFile = osPath.toAbsolutePath().normalize();
        if (!normFile.startsWith(normRoot)) {
            return null;
        }
        return normRoot.relativize(normFile).toString().replace('\\', '/');
    }

    private static Path resourceToPath(IResource resource) {
        if (resource == null) {
            return null;
        }
        IPath loc = resource.getLocation();
        if (loc != null) {
            return loc.toFile().toPath().toAbsolutePath().normalize();
        }
        URI uri = resource.getLocationURI();
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
            return Path.of(uri).toAbsolutePath().normalize();
        }
        return null;
    }

    private static GitDiffModel.GitDiffSummary unavailable(Path repoRoot) {
        return new GitDiffModel.GitDiffSummary(false, repoRoot == null ? null : repoRoot.toString(), Map.of());
    }

    private record GitExecResult(int exitCode, String stdout, boolean timedOut) {}

    private static GitExecResult runGit(Path repoRoot, List<String> args) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add("git");
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoRoot.toFile());
        Process process = null;
        try {
            process = pb.start();
            Process started = process;
            AtomicReference<String> stdoutRef = new AtomicReference<>("");
            Thread outThread = Thread.ofVirtual().start(() -> stdoutRef.set(readAllUtf8(started.getInputStream())));
            Thread errThread = Thread.ofVirtual().start(() -> drainStream(started.getErrorStream()));

            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                outThread.join(250);
                errThread.join(250);
                return new GitExecResult(-1, "", true);
            }
            outThread.join(1000);
            errThread.join(1000);
            return new GitExecResult(process.exitValue(), stdoutRef.get(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new GitExecResult(-1, "", false);
        } catch (Exception e) {
            return new GitExecResult(-1, "", false);
        }
    }

    private static String readAllUtf8(InputStream in) {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            stream.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void drainStream(InputStream in) {
        try (InputStream stream = in) {
            stream.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
        }
    }
}
