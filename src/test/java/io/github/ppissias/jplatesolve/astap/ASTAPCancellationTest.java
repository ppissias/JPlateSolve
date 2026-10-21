package io.github.ppissias.jplatesolve.astap;

import io.github.ppissias.jplatesolve.PlateSolveOperation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ASTAPCancellationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private PlateSolveOperation start(Path image, String mode, Duration budget) throws Exception {
        return start(image, mode, budget, false);
    }

    private PlateSolveOperation start(Path image, String mode, Duration budget, boolean ignoreGracefulStop) throws Exception {
        String classes = Path.of(AstapProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return ASTAPInterface.startSolve(new File("unused"), image.toString(), budget, null, (executable, source) -> {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process process = new ProcessBuilder(java, "-cp", classes, AstapProcessFixture.class.getName(), source, mode)
                    .redirectErrorStream(true).start();
            return ignoreGracefulStop ? new Process() {
                @Override public java.io.OutputStream getOutputStream() { return process.getOutputStream(); }
                @Override public java.io.InputStream getInputStream() { return process.getInputStream(); }
                @Override public java.io.InputStream getErrorStream() { return process.getErrorStream(); }
                @Override public int waitFor() throws InterruptedException { return process.waitFor(); }
                @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return process.waitFor(timeout, unit); }
                @Override public int exitValue() { return process.exitValue(); }
                @Override public boolean isAlive() { return process.isAlive(); }
                @Override public ProcessHandle toHandle() { return process.toHandle(); }
                @Override public void destroy() { }
                @Override public Process destroyForcibly() { process.destroyForcibly(); return this; }
            } : process;
        });
    }

    private Path image(String filename) throws Exception {
        Path path = folder.getRoot().toPath().resolve(filename);
        return Files.writeString(path, "input");
    }

    private long awaitPid(Path image) throws Exception {
        Path marker = ASTAPSolveResultsReader.sidecarPath(image.toString(), ".pid");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(marker) || Files.size(marker) == 0) {
            if (System.nanoTime() > deadline) fail("Child did not start");
            Thread.sleep(10);
        }
        return Long.parseLong(Files.readString(marker));
    }

    @Test
    public void cancellationKillsProcessAndRestoresPreexistingOutputs() throws Exception {
        Path image = image("frame.fits");
        Path ini = folder.getRoot().toPath().resolve("frame.ini");
        Path wcs = folder.getRoot().toPath().resolve("frame.wcs");
        Files.writeString(ini, "old-ini");
        Files.writeString(wcs, "old-wcs");
        PlateSolveOperation solve = start(image, "hang", Duration.ofSeconds(10));
        long pid = awaitPid(image);
        long cancel = System.nanoTime();
        assertTrue(solve.cancel());
        assertEquals(PlateSolveOperation.Status.CANCELLED, solve.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertEquals("old-ini", Files.readString(ini));
        assertEquals("old-wcs", Files.readString(wcs));
        assertEquals("input", Files.readString(image));
        System.out.println("ASTAP fixture Cancel to process-dead/completion ms: " + (System.nanoTime() - cancel) / 1_000_000.0);
    }

    @Test
    public void deadlineKillsTheChildBeforeCompletion() throws Exception {
        Path image = image("timeout.fit");
        PlateSolveOperation solve = start(image, "hang", Duration.ofSeconds(2));
        long pid = awaitPid(image);
        assertEquals(PlateSolveOperation.Status.TIMED_OUT, solve.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertFalse(Files.exists(folder.getRoot().toPath().resolve("timeout.wcs")));
    }

    @Test
    public void drainsVerboseProcessAndKeepsFreshSuccessfulOutputs() throws Exception {
        Path image = image("verbose.fit");
        PlateSolveOperation solve = start(image, "verbose", Duration.ofSeconds(10));
        var outcome = solve.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
        assertEquals("new-wcs", Files.readString(folder.getRoot().toPath().resolve("verbose.wcs")));
    }

    @Test
    public void nonzeroExitCanStillReportAnExplicitRejection() throws Exception {
        var outcome = start(image("reject.fit"), "reject", Duration.ofSeconds(5))
                .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(PlateSolveOperation.Status.REJECTED, outcome.status());
        assertEquals("No solution", outcome.result().getFailureReason());
        assertFalse(Files.exists(folder.getRoot().toPath().resolve("reject.wcs")));
    }

    @Test
    public void staleWcsCannotMakeAnIncompleteSolveSucceed() throws Exception {
        Path image = image("stale.fit");
        Path wcs = folder.getRoot().toPath().resolve("stale.wcs");
        Files.writeString(wcs, "old-wcs");
        var outcome = start(image, "missing-wcs", Duration.ofSeconds(5))
                .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(PlateSolveOperation.Status.FAILED, outcome.status());
        assertEquals("old-wcs", Files.readString(wcs));
    }

    @Test
    public void extensionlessImagesDoNotTruncateTheInputPath() throws Exception {
        Path image = image("extensionless");
        var outcome = start(image, "success", Duration.ofSeconds(5))
                .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(PlateSolveOperation.Status.SUCCEEDED, outcome.status());
        assertEquals("input", Files.readString(image));
        assertTrue(Files.exists(folder.getRoot().toPath().resolve("extensionless.wcs")));
    }

    @Test
    public void overlappingFitAndFitsStemsCannotClaimEachOthersFiles() throws Exception {
        Path first = image("same.fit");
        PlateSolveOperation solve = start(first, "hang", Duration.ofSeconds(10));
        try {
            awaitPid(first);
            var other = start(image("same.fits"), "success", Duration.ofSeconds(5))
                    .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(PlateSolveOperation.Status.FAILED, other.status());
            assertTrue(other.cause().getMessage().contains("already owns"));
        } finally {
            solve.cancel();
            solve.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void aLaunchFailureRestoresItsBackups() throws Exception {
        Path image = image("launch.fit");
        Path ini = folder.getRoot().toPath().resolve("launch.ini");
        Files.writeString(ini, "old-ini");
        var operation = ASTAPInterface.startSolve(new File("missing"), image.toString(), Duration.ofSeconds(5), null,
                (executable, source) -> { throw new java.io.IOException("Cannot launch"); });
        assertEquals(PlateSolveOperation.Status.FAILED, operation.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertEquals("old-ini", Files.readString(ini));
    }

    @Test
    public void anOutputExtensionCannotCauseTheSourceToBeMovedOrDeleted() throws Exception {
        Path image = image("source.wcs");
        var operation = start(image, "success", Duration.ofSeconds(5));
        assertEquals(PlateSolveOperation.Status.FAILED, operation.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertEquals("input", Files.readString(image));
    }

    @Test
    public void cancellationEscalatesWhenGracefulDestructionDoesNotStopTheProcess() throws Exception {
        Path image = image("force.fit");
        PlateSolveOperation operation = start(image, "hang", Duration.ofSeconds(10), true);
        long pid = awaitPid(image);
        assertTrue(operation.cancel());
        assertEquals(PlateSolveOperation.Status.CANCELLED, operation.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
