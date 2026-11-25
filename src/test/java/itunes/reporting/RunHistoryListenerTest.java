package itunes.reporting;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import itunes.reporting.RunHistoryListener.Result;
import itunes.reporting.RunHistoryListener.Run;
import itunes.reporting.RunHistoryListener.Status;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/** Offline tests of the run history (no API calls); they write only to a temporary folder. */
@DisplayName("run history report")
class RunHistoryListenerTest {

    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 11, 25, 9, 30, 15);
    private static final List<Result> RESULTS = List.of(
            new Result("country parameter", "TC-C01 <b>&\"'", Status.PASSED, 1200, "", ""),
            new Result("country parameter", "TC-C02", Status.FAILED, 300, "expected: <1>\nbut was: <2>", "trace line"),
            new Result("limit parameter", "TC-L06", Status.SKIPPED, -1, "disabled", ""),
            new Result("limit parameter", "TC-L07", Status.ABORTED, 5, "assumption", "trace"));

    @TempDir
    Path dir;

    private RunHistoryListener listener(String keep, String filter) {
        return new RunHistoryListener(dir.toString(), keep, filter);
    }

    private List<String> files() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("each run gets the next number and is listed in runs.tsv and the index")
    void savesNumberedRuns() throws IOException {
        RunHistoryListener l = listener(null, null);
        Run first = l.save(dir, RESULTS, STARTED, 2500);
        Run second = l.save(dir, RESULTS, STARTED, 2500);

        assertAll(
                () -> assertEquals(1, first.number()),
                () -> assertEquals(2, second.number()),
                () -> assertEquals("run-0001_2026-11-25_09-30-15.html", first.file()),
                () -> assertEquals(List.of(1, 1, 1, 1), List.of(first.passed(), first.failed(), first.aborted(), first.skipped())),
                () -> assertFalse(first.green(), "a failed test makes the run red"),
                () -> assertEquals(List.of("index.html", "run-0001_2026-11-25_09-30-15.html",
                        "run-0002_2026-11-25_09-30-15.html", "runs.tsv"), files(), "no temporary files left"),
                () -> assertEquals(2, RunHistoryListener.readRuns(dir).size()));
        String index = Files.readString(dir.resolve("index.html"), StandardCharsets.UTF_8);
        // Table links look like ">#2</a>"; the "Latest: run #2" line above the table doesn't.
        assertTrue(index.indexOf("\">#2</a>") < index.indexOf("\">#1</a>"), "the latest run is listed first");
    }

    @Test
    @DisplayName("a run chosen with -Dtest is marked filtered")
    void filteredRun() throws IOException {
        Run run = listener(null, "CountryTest").save(dir, RESULTS, STARTED, 10);
        assertAll(
                () -> assertEquals("filtered", run.type()),
                () -> assertEquals("run-0001_2026-11-25_09-30-15_filtered.html", run.file()),
                () -> assertTrue(Files.readString(dir.resolve(run.file())).contains("(-Dtest=CountryTest)")));
    }

    @Test
    @DisplayName("the report escapes text, shows skipped times as a dash and failure details")
    void reportContent() throws IOException {
        Run run = listener(null, null).save(dir, RESULTS, STARTED, 10);
        String html = Files.readString(dir.resolve(run.file()), StandardCharsets.UTF_8);
        assertAll(
                () -> assertTrue(html.contains("TC-C01 &lt;b&gt;&amp;&quot;&#39;"), "test names are escaped"),
                () -> assertFalse(html.contains("<b>&")),
                () -> assertTrue(html.contains("<summary>expected: &lt;1&gt;</summary>"), "first message line as summary"),
                () -> assertTrue(html.contains("<pre>trace line</pre>")),
                () -> assertTrue(html.contains(">–</td>"), "skipped tests have no time"));
    }

    @Test
    @DisplayName("only the newest historyKeep runs are kept, and numbers keep counting up")
    void prunesOldRuns() throws IOException {
        RunHistoryListener l = listener("2", null);
        for (int i = 0; i < 3; i++) {
            l.save(dir, RESULTS, STARTED.plusMinutes(i), 10);
        }
        assertAll(
                () -> assertEquals(List.of(2, 3), RunHistoryListener.readRuns(dir).stream().map(Run::number).toList()),
                () -> assertFalse(Files.exists(dir.resolve("run-0001_2026-11-25_09-30-15.html"))),
                () -> assertEquals(4, l.save(dir, RESULTS, STARTED, 10).number()));
    }

    @Test
    @DisplayName("historyKeep=0 keeps every run")
    void keepZeroKeepsAll() throws IOException {
        RunHistoryListener l = listener("0", null);
        for (int i = 0; i < 4; i++) {
            l.save(dir, RESULTS, STARTED.plusMinutes(i), 10);
        }
        assertEquals(4, RunHistoryListener.readRuns(dir).size());
    }

    @Test
    @DisplayName("an old report that lost its runs.tsv line is still deleted, and still counts for numbering")
    void deletesOrphanedReports() throws IOException {
        Files.writeString(dir.resolve("run-0007_2026-01-01_00-00-00.html"), "orphan");
        assertEquals(8, RunHistoryListener.nextNumber(dir, List.of()));

        RunHistoryListener l = listener("1", null);
        l.save(dir, RESULTS, STARTED, 10);   // run 8; run 7 is older than every kept run
        assertFalse(Files.exists(dir.resolve("run-0007_2026-01-01_00-00-00.html")));
    }

    @Test
    @DisplayName("a missing runs.tsv doesn't cause deletions while there are no more than historyKeep runs")
    void missingRunsFileKeepsReports() throws IOException {
        for (int n = 1; n <= 3; n++) {
            Files.writeString(dir.resolve("run-000" + n + "_2026-01-01_00-00-00.html"), "report " + n);
        }
        Run run = listener("50", null).save(dir, RESULTS, STARTED, 10);
        assertAll(
                () -> assertEquals(4, run.number()),
                () -> assertEquals(4, files().stream().filter(f -> f.startsWith("run-")).count(),
                        "all three older reports are still there, plus the new one"));
    }

    @Test
    @DisplayName("pruning counts reports without a runs.tsv line too, and deletes only beyond historyKeep")
    void pruningCountsReportsOnDisk() throws IOException {
        Files.writeString(dir.resolve("run-0001_2026-01-01_00-00-00.html"), "lost line");
        Files.writeString(dir.resolve("run-0002_2026-01-01_00-00-00.html"), "lost line");
        listener("2", null).save(dir, RESULTS, STARTED, 10);   // run 3: keeps #2 and #3
        assertAll(
                () -> assertFalse(Files.exists(dir.resolve("run-0001_2026-01-01_00-00-00.html"))),
                () -> assertTrue(Files.exists(dir.resolve("run-0002_2026-01-01_00-00-00.html"))),
                () -> assertEquals(3, RunHistoryListener.cutoff(dir, RunHistoryListener.readRuns(dir), 1)));
    }

    @Test
    @DisplayName("the index and runs.tsv have the same permissions as the reports (not owner-only)")
    void indexIsReadable() throws IOException {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class), "POSIX only");
        Run run = listener(null, null).save(dir, RESULTS, STARTED, 10);
        Set<PosixFilePermission> report = Files.getPosixFilePermissions(dir.resolve(run.file()));
        assertAll(
                () -> assertEquals(report, Files.getPosixFilePermissions(dir.resolve("index.html"))),
                () -> assertEquals(report, Files.getPosixFilePermissions(dir.resolve("runs.tsv"))));
    }

    @Test
    @DisplayName("damaged runs.tsv lines are dropped, including file names that leave the folder")
    void readsDamagedRunsFile() throws IOException {
        Path history = Files.createDirectory(dir.resolve("history"));
        Files.writeString(history.resolve("runs.tsv"), String.join("\n",
                "1\t2026-11-25 09:00:00\tfull\t1\t0\t0\t0\t10\trun-0001_a.html",
                "2\tonly three\tfields",
                "x\t2026-11-25 09:00:00\tfull\t1\t0\t0\t0\t10\trun-0003_a.html",
                "4\t2026-11-25 09:00:00\tfull\t1\t0\t0\t0\t10\trun-4_/../../outside.html",
                ""));
        assertEquals(List.of(1), RunHistoryListener.readRuns(history).stream().map(Run::number).toList());
    }

    @Test
    @DisplayName("invalid bytes in runs.tsv drop only that line, and the next run is still saved")
    void readsRunsFileWithInvalidBytes() throws IOException {
        byte[] valid = "1\t2026-11-25 09:00:00\tfull\t1\t0\t0\t0\t10\trun-0001_a.html\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[valid.length + 2];
        content[0] = (byte) 0xC3;   // a truncated UTF-8 sequence
        content[1] = '\n';
        System.arraycopy(valid, 0, content, 2, valid.length);
        Files.write(dir.resolve("runs.tsv"), content);

        assertEquals(List.of(1), RunHistoryListener.readRuns(dir).stream().map(Run::number).toList());
        assertEquals(2, listener(null, null).save(dir, RESULTS, STARTED, 10).number());
    }

    @Test
    @DisplayName("temporary files left by a killed run are deleted once they're stale, fresh ones are kept")
    void deletesStaleTempFiles() throws IOException {
        Path stale = Files.writeString(dir.resolve("index.html.123.tmp"), "left over");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        Path fresh = Files.writeString(dir.resolve("runs.tsv.456.tmp"), "being written");
        Path other = Files.writeString(dir.resolve("notes.tmp"), "not ours");
        Files.setLastModifiedTime(other, FileTime.from(Instant.now().minus(Duration.ofHours(1))));

        RunHistoryListener.deleteStaleTempFiles(dir);
        assertAll(
                () -> assertFalse(Files.exists(stale)),
                () -> assertTrue(Files.exists(fresh), "a save in progress keeps its file"),
                () -> assertTrue(Files.exists(other), "only the listener's own temporary files are deleted"));
    }

    @ParameterizedTest(name = "historyKeep=\"{0}\" -> {1}")
    @CsvSource(value = {"NULL, 50", "'', 50", "' 7 ', 7", "0, 0", "-1, 50", "abc, 50"}, nullValues = "NULL")
    @DisplayName("historyKeep falls back to 50 when it isn't a whole number >= 0")
    void parsesKeep(String value, int expected) {
        assertEquals(expected, RunHistoryListener.parseKeep(value));
    }

    @Test
    @DisplayName("a blank historyDir means the default folder")
    void blankDirIsDefault() {
        assertAll(
                () -> assertEquals(Paths.get("test-history"), new RunHistoryListener("  ", null, null).historyDir()),
                () -> assertEquals(Paths.get("test-history"), new RunHistoryListener(null, null, null).historyDir()),
                () -> assertEquals(Paths.get("x"), new RunHistoryListener("x", null, null).historyDir()));
    }

    @ParameterizedTest(name = "{0} ms -> {1}")
    @CsvSource({"0, 0 ms", "999, 999 ms", "1000, 1 s", "59499, 59 s", "59500, 1 min 0 s", "125000, 2 min 5 s"})
    @DisplayName("durations are shown in ms, s or min and s")
    void formatsDurations(long millis, String expected) {
        assertEquals(expected, RunHistoryListener.duration(millis));
    }

    @Test
    @DisplayName("the pass rate leaves out skipped and aborted tests")
    void passRate() {
        assertAll(
                () -> assertEquals("75%", RunHistoryListener.passRate(new Run(1, "", "full", 3, 1, 2, 5, 0, "run-1_a.html"))),
                () -> assertEquals("–", RunHistoryListener.passRate(new Run(1, "", "full", 0, 0, 1, 1, 0, "run-1_a.html"))),
                () -> assertEquals("99%", RunHistoryListener.passRate(new Run(1, "", "full", 199, 1, 0, 0, 0, "run-1_a.html")),
                        "a run with a failure never shows 100%"),
                () -> assertEquals("100%", RunHistoryListener.passRate(new Run(1, "", "full", 200, 0, 0, 0, 0, "run-1_a.html"))));
    }

    // ---------------------------------------------------------------------------------------------
    // How JUnit's events become rows: sample classes run through a launcher of their own, with only this
    // listener registered (the real one isn't), so nothing is written outside the temporary folder.
    // Surefire skips nested classes, so `mvn test` never runs them directly; an IDE run of the whole
    // package does discover them, and reports their 7 tests as skipped (disabled outside runFixtures).

    /** Set only while {@link #runFixtures} runs, so the sample classes never run on their own (e.g. in the IDE). */
    private static volatile boolean fixturesEnabled;

    static boolean fixturesEnabled() {
        return fixturesEnabled;
    }

    private static void runFixtures(RunHistoryListener listener, Class<?>... classes) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(Arrays.stream(classes).map(DiscoverySelectors::selectClass).toList())
                .build();
        Launcher launcher = LauncherFactory.create(LauncherConfig.builder()
                .enableTestExecutionListenerAutoRegistration(false)
                .build());
        fixturesEnabled = true;
        try {
            launcher.execute(request, listener);
        } finally {
            fixturesEnabled = false;
        }
    }

    @Test
    @DisplayName("JUnit results become rows: passed, failed, aborted, skipped and failed class setup")
    void recordsJUnitEvents() throws IOException {
        runFixtures(listener(null, null), SetupFails.class, DisabledClass.class, Mixed.class);

        Run run = RunHistoryListener.readRuns(dir).get(0);
        String html = Files.readString(dir.resolve(run.file()), StandardCharsets.UTF_8);
        assertAll(
                () -> assertEquals(1, run.passed(), "Mixed.passes"),
                () -> assertEquals(2, run.failed(), "Mixed.fails and SetupFails' @BeforeAll"),
                () -> assertEquals(1, run.aborted(), "Mixed.aborted (failed assumption)"),
                () -> assertEquals(3, run.skipped(), "both tests of DisabledClass and the @Disabled parameterized test"),
                () -> assertTrue(html.contains("fixture: setup fails (class setup/teardown)")),
                () -> assertTrue(html.contains("setup broke")),
                () -> assertTrue(html.contains("<h2>fixture: disabled class")),
                () -> assertFalse(html.contains("never runs"), "tests of a class whose setup failed aren't listed"));
    }

    @Test
    @DisplayName("each test plan is its own run, even with the same listener")
    void eachPlanIsItsOwnRun() throws IOException {
        RunHistoryListener l = listener(null, null);
        runFixtures(l, Mixed.class);
        runFixtures(l, DisabledClass.class);

        List<Run> runs = RunHistoryListener.readRuns(dir);
        assertAll(
                () -> assertEquals(2, runs.size()),
                () -> assertEquals(4, runs.get(0).total()),
                () -> assertEquals(2, runs.get(1).total(), "the second run doesn't repeat the first run's results"),
                () -> assertEquals(2, runs.get(1).skipped()));
    }

    @Test
    @DisplayName("a history folder that can't be written only prints a warning")
    void unwritableFolderOnlyWarns() throws IOException {
        Path notAFolder = Files.writeString(dir.resolve("a-file"), "not a folder");
        // The launcher itself swallows exceptions from listeners, so the proof that the listener handled
        // the problem is its own warning.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            runFixtures(new RunHistoryListener(notAFolder.toString(), null, null), Mixed.class);
        } finally {
            System.setErr(originalErr);
        }
        assertAll(
                () -> assertTrue(err.toString(StandardCharsets.UTF_8)
                        .contains("[run history] WARNING: this run's history wasn't saved"), err::toString),
                () -> assertEquals("not a folder", Files.readString(notAFolder)));
    }

    @DisplayName("fixture: setup fails")
    @EnabledIf("itunes.reporting.RunHistoryListenerTest#fixturesEnabled")
    static class SetupFails {
        @BeforeAll
        static void breakSetup() {
            throw new IllegalStateException("setup broke");
        }

        @Test
        @DisplayName("never runs")
        void neverRuns() {
            // not reached: the class setup fails
        }
    }

    @DisplayName("fixture: disabled class")
    @Disabled("fixture for RunHistoryListenerTest")
    static class DisabledClass {
        @Test
        void first() {
            // skipped with its class
        }

        @Test
        void second() {
            // skipped with its class
        }
    }

    @DisplayName("fixture: mixed results")
    @EnabledIf("itunes.reporting.RunHistoryListenerTest#fixturesEnabled")
    static class Mixed {
        @Test
        void passes() {
            // passes
        }

        @Test
        void fails() {
            fail("expected <1>");
        }

        @Test
        void aborted() {
            assumeTrue(false, "not applicable");
        }

        @Disabled("fixture")
        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        void disabledParameterized(int value) {
            assertTrue(value > 0);
        }
    }
}
