/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jplatesolve.astap;

import io.github.ppissias.jplatesolve.PlateSolveOperation;
import io.github.ppissias.jplatesolve.PlateSolveOperation.CleanupException;
import io.github.ppissias.jplatesolve.PlateSolveOperation.Phase;
import io.github.ppissias.jplatesolve.PlateSolveResult;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Runs ASTAP and owns its process and output files until solve completion. */
public class ASTAPInterface {
    private static final AtomicInteger WORKERS = new AtomicInteger();
    private static final Set<Path> ACTIVE_STEMS = ConcurrentHashMap.newKeySet();

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(File executable, String image) throws IOException;
    }

    public static Future<PlateSolveResult> solveImage(File executable, String image) {
        return solveImage(executable, image, ASTAPSolveResultsReader.DEFAULT_RESULT_TIMEOUT);
    }

    /** Legacy adapter; use startSolve to wait for cleanup after requesting cancellation. */
    public static Future<PlateSolveResult> solveImage(File executable, String image, Duration timeout) {
        return startSolve(executable, image, timeout, null).asFuture(false);
    }

    public static PlateSolveOperation startSolve(File executable, String image, Duration timeout,
                                                 Consumer<PlateSolveOperation.Progress> progress) {
        return startSolve(executable, image, timeout, progress, (program, source) ->
                new ProcessBuilder(program.getAbsolutePath(), "-f", source, "-r", "360",
                        "-z", "0", "-fov", "0", "-wcs", "-annotate")
                        .directory(program.getAbsoluteFile().getParentFile())
                        .redirectErrorStream(true).start());
    }

    static PlateSolveOperation startSolve(File executable, String image, Duration timeout,
                                          Consumer<PlateSolveOperation.Progress> progress, ProcessLauncher launcher) {
        Duration budget = timeout == null || timeout.isNegative() || timeout.isZero()
                ? ASTAPSolveResultsReader.DEFAULT_RESULT_TIMEOUT : timeout;
        return PlateSolveOperation.start(budget, progress, action -> {
            Thread thread = new Thread(action, "jplatesolve-astap-" + WORKERS.incrementAndGet());
            thread.setDaemon(true);
            thread.start();
        }, context -> {
            context.report(Phase.PREPARING, "Preparing ASTAP outputs");
            Path source = Path.of(image).toAbsolutePath();
            Path canonicalSource = source.getParent().toRealPath().resolve(source.getFileName());
            Path stem = ASTAPSolveResultsReader.sidecarPath(canonicalSource.toString(), ".ini");
            if (canonicalSource.equals(stem) || canonicalSource.equals(ASTAPSolveResultsReader.sidecarPath(canonicalSource.toString(), ".wcs"))) {
                throw new IOException("ASTAP sidecars would overwrite the input: " + canonicalSource);
            }
            if (!ACTIVE_STEMS.add(stem)) throw new IOException("An ASTAP solve already owns output stem: " + stem);
            SolveFiles files = new SolveFiles(canonicalSource.toString(), stem);
            LocalProcess process = new LocalProcess();
            context.onCleanup(outcome -> files.finish(outcome, process));
            context.onCleanup(outcome -> process.close());
            files.prepare();
            context.report(Phase.LAUNCHING, "Starting ASTAP");
            process.process = launcher.start(executable, source.toString());
            process.startDrainer();
            context.report(Phase.SOLVING, "ASTAP is solving");
            while (!process.process.waitFor(Math.min(100, Math.max(1, context.remaining().toMillis())), TimeUnit.MILLISECONDS)) {
                process.observeChildren();
                context.checkCancelled();
            }
            context.report(Phase.READING_RESULT, "Reading ASTAP result");
            if (!Files.isRegularFile(ASTAPSolveResultsReader.sidecarPath(image, ".ini"))) {
                throw new IOException("ASTAP exited with code " + process.process.exitValue()
                        + " without a result file. " + process.diagnostics());
            }
            PlateSolveResult result = new ASTAPSolveResultsReader(image, context.remaining()).getSolveResult();
            context.checkCancelled();
            if (result.isSuccess() && !Files.isRegularFile(ASTAPSolveResultsReader.sidecarPath(image, ".wcs"))) {
                throw new IOException("ASTAP reported success without producing a WCS file.");
            }
            return result;
        });
    }

    private static final class SolveFiles {
        private final Path stem;
        private final List<Path> outputs;
        private final List<Path> saved = new ArrayList<>();
        private Path backup;
        private boolean prepared;

        private SolveFiles(String image, Path stem) {
            this.stem = stem;
            this.outputs = List.of(ASTAPSolveResultsReader.sidecarPath(image, ".ini"),
                    ASTAPSolveResultsReader.sidecarPath(image, ".wcs"),
                    ASTAPSolveResultsReader.sidecarPath(image, "_annotated.jpg"));
        }

        private void prepare() throws IOException {
            backup = Files.createTempDirectory(stem.getParent(), ".jplatesolve-astap-");
            for (Path output : outputs) {
                if (Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(output) || !Files.isRegularFile(output)) {
                        throw new IOException("ASTAP output is not an ordinary file: " + output);
                    }
                    Files.move(output, backup.resolve(output.getFileName()));
                    saved.add(output);
                }
            }
            prepared = true;
        }

        private void finish(PlateSolveOperation.Outcome outcome, LocalProcess process) throws IOException {
            if (!process.stopped()) throw new CleanupException("ASTAP is still alive; output backups retained at " + backup);
            try {
                if (outcome.status() != PlateSolveOperation.Status.SUCCEEDED) {
                    if (prepared) for (Path output : outputs) Files.deleteIfExists(output);
                    for (Path output : saved) Files.move(backup.resolve(output.getFileName()), output);
                } else {
                    for (Path output : saved) Files.deleteIfExists(backup.resolve(output.getFileName()));
                }
                if (backup != null) Files.deleteIfExists(backup);
            } catch (IOException failure) {
                throw new CleanupException("Cannot finalize ASTAP outputs; recovery files are at " + backup, failure);
            } finally {
                ACTIVE_STEMS.remove(stem);
            }
        }
    }

    private static final class LocalProcess {
        private Process process;
        private Thread drainer;
        private final Set<ProcessHandle> children = new LinkedHashSet<>();
        private String tail = "";

        private void startDrainer() {
            drainer = new Thread(() -> {
                try (InputStream input = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        synchronized (this) {
                            tail += new String(buffer, 0, count, StandardCharsets.UTF_8);
                            if (tail.length() > 16384) tail = tail.substring(tail.length() - 16384);
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "jplatesolve-astap-output-" + WORKERS.incrementAndGet());
            drainer.setDaemon(true);
            drainer.start();
        }

        private synchronized String diagnostics() { return tail; }

        private void observeChildren() {
            process.descendants().forEach(children::add);
        }

        private boolean stopped() {
            return (process == null || !process.isAlive()) && children.stream().noneMatch(ProcessHandle::isAlive);
        }

        private void close() throws Exception {
            if (process == null) return;
            observeChildren();
            if (!stopped()) {
                children.forEach(ProcessHandle::destroy);
                if (process.isAlive()) process.destroy();
                long grace = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
                while (!stopped() && System.nanoTime() < grace) Thread.sleep(10);
                children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!stopped() && System.nanoTime() < deadline) Thread.sleep(10);
            }
            process.getOutputStream().close();
            process.getInputStream().close();
            process.getErrorStream().close();
            if (drainer != null) drainer.join(1000);
            if (!stopped() || drainer != null && drainer.isAlive()) {
                throw new CleanupException("Cannot confirm ASTAP process and output reader termination.");
            }
        }
    }
}
