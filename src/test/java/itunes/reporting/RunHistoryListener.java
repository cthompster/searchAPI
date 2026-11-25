package itunes.reporting;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Keeps a numbered HTML report of every test run, so earlier results aren't lost when the next run
 * overwrites {@code target/surefire-reports}. Registered through
 * {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}, so it covers runs from
 * Maven and from the IDE alike.
 *
 * <p>Each run is written to {@code <historyDir>/run-NNNN_yyyy-MM-dd_HH-mm-ss[_filtered].html} (every test
 * with its result and time and, for a failure, the message and stack trace) and listed in
 * {@code <historyDir>/index.html} from the latest run back to the first, with its counts and a trend.
 * {@code runs.tsv} is the list the index is built from. A run is marked {@code filtered} only when Maven's
 * {@code -Dtest=…} chose the tests; a single class run from the IDE, or {@code -Dgroups}, shows as full.
 * The numbers keep counting up; only the newest {@code historyKeep} runs are kept.
 *
 * <p>A run is one JUnit test plan. With this project's Surefire settings (one fork) that is the whole
 * {@code mvn test}; with {@code -DforkCount=2} or more, or {@code -Dsurefire.rerunFailingTestsCount}, Surefire
 * runs several plans, and each is saved as its own run (a rerun of failed tests is then labelled as full).
 *
 * <p>Results: passed, failed, skipped ({@code @Disabled}) and aborted (a failed assumption, which like
 * Surefire doesn't count as a failure). A class whose {@code @BeforeAll}/{@code @AfterAll} fails is listed
 * as its own row "… (class setup/teardown)", since its tests may never run; another failed container (a
 * parameterized test whose arguments can't be produced) as "… (container)".
 *
 * <p>Settings (override with {@code -Dkey=value}):
 * <ul>
 *   <li>{@code historyDir}: default {@code test-history} (git-ignored, outside {@code target/}, so
 *       {@code mvn clean} keeps it); blank means the default</li>
 *   <li>{@code historyKeep}: how many runs to keep, default 50; 0 keeps all</li>
 * </ul>
 *
 * <p>Problems writing the history, invalid settings included, are printed as warnings and never fail the
 * test run. Two runs at the same time get different numbers, but one of them can drop the other's line from
 * {@code runs.tsv}. Pruning counts the report files on disk as well as runs.tsv, so a lost line never
 * causes extra deletions, and a report that lost its line is still deleted once it's no longer among the
 * newest {@code historyKeep}.
 */
public final class RunHistoryListener implements TestExecutionListener {

    static final String DEFAULT_DIR = "test-history";
    static final int DEFAULT_KEEP = 50;
    static final String RUNS_FILE = "runs.tsv";
    static final String INDEX_FILE = "index.html";
    /** A report written by this class: a plain file name, so it can't point outside the history folder. */
    static final Pattern REPORT_FILE = Pattern.compile("run-(\\d{1,9})_[A-Za-z0-9_-]+\\.html");
    /** A temporary file of {@link #replace}: {@code index.html.<random>.tmp} or {@code runs.tsv.<random>.tmp}. */
    static final Pattern TEMP_FILE = Pattern.compile("(index\\.html|runs\\.tsv)\\.[0-9]+\\.tmp");
    static final Duration STALE_TEMP = Duration.ofMinutes(10);

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final DateTimeFormatter SHOWN_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final int MAX_TRACE_LINES = 40;

    enum Status { PASSED, FAILED, ABORTED, SKIPPED }

    /**
     * One test (or a container whose setup or teardown failed) in a run. {@code millis} is -1 for a skipped
     * test, which has no time (shown as "–").
     */
    record Result(String group, String name, Status status, long millis, String message, String trace) { }

    /** One line of runs.tsv. */
    record Run(int number, String started, String type, int passed, int failed, int aborted, int skipped,
               long millis, String file) {
        int total() {
            return passed + failed + aborted + skipped;
        }

        /** Nothing failed (aborted and skipped tests don't count as failures, as in Surefire). */
        boolean green() {
            return failed == 0;
        }
    }

    private final String dirSetting;
    private final int keep;
    private final String testFilter;
    private final List<Result> results = new ArrayList<>();
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();
    private TestPlan plan;
    private LocalDateTime started = LocalDateTime.now();
    private long startedNanos = System.nanoTime();

    /** Used by the JUnit launcher; reads the settings from system properties. Never throws. */
    public RunHistoryListener() {
        this(System.getProperty("historyDir"), System.getProperty("historyKeep"), System.getProperty("test"));
    }

    RunHistoryListener(String dirSetting, String keepSetting, String testFilter) {
        this.dirSetting = dirSetting;
        this.keep = parseKeep(keepSetting);
        this.testFilter = testFilter == null || testFilter.isBlank() ? null : testFilter;
    }

    // ---------------------------------------------------------------------------------------------
    // Collecting results

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        // One listener can see several plans (e.g. Surefire re-running failed tests): each is its own run.
        synchronized (results) {
            results.clear();
        }
        startedAt.clear();
        plan = testPlan;
        started = LocalDateTime.now();
        startedNanos = System.nanoTime();
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        startedAt.put(id.getUniqueId(), System.nanoTime());
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        // A skipped container (a disabled class or @ParameterizedTest) counts each of its leaves; tests
        // generated at run time (parameterized invocations) aren't known, so their method counts once.
        List<TestIdentifier> leaves = plan == null ? List.of(id) : leavesOf(id);
        for (TestIdentifier leaf : leaves) {
            add(leaf, leaf.getDisplayName(), Status.SKIPPED, -1, reason == null ? "" : reason, "");
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        Long start = startedAt.remove(id.getUniqueId());
        long millis = start == null ? 0 : Duration.ofNanos(System.nanoTime() - start).toMillis();
        boolean ok = result.getStatus() == TestExecutionResult.Status.SUCCESSFUL;
        // Containers are listed only when they fail (a @BeforeAll/@AfterAll), since their tests may not run.
        if (!id.isTest() && ok) {
            return;
        }
        Status status = switch (result.getStatus()) {
            case SUCCESSFUL -> Status.PASSED;
            case ABORTED -> Status.ABORTED;
            case FAILED -> Status.FAILED;
        };
        String name = id.isTest() ? id.getDisplayName()
                : id.getDisplayName() + (id.getSource().filter(ClassSource.class::isInstance).isPresent()
                        ? " (class setup/teardown)" : " (container)");
        Optional<Throwable> error = result.getThrowable();
        add(id, name, status, millis, error.map(Throwable::toString).orElse(""),
                error.map(RunHistoryListener::trace).orElse(""));
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        List<Result> snapshot;
        synchronized (results) {
            snapshot = List.copyOf(results);
        }
        if (snapshot.isEmpty()) {
            return;   // nothing ran (e.g. -Dtest matched no test)
        }
        long millis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        try {
            Path dir = historyDir();
            Run run = save(dir, snapshot, started, millis);
            System.out.println("[run history] Run #" + run.number() + " saved: "
                    + dir.resolve(run.file()).toAbsolutePath().normalize() + " (all runs: "
                    + dir.resolve(INDEX_FILE).toAbsolutePath().normalize() + ")");
        } catch (IOException | RuntimeException e) {
            System.err.println("[run history] WARNING: this run's history wasn't saved: " + e);
        }
    }

    private void add(TestIdentifier id, String name, Status status, long millis, String message, String trace) {
        Result r = new Result(groupOf(id), name, status, millis, message, trace);
        synchronized (results) {
            results.add(r);
        }
    }

    private List<TestIdentifier> leavesOf(TestIdentifier id) {
        List<TestIdentifier> leaves = new ArrayList<>();
        for (TestIdentifier child : plan.getChildren(id)) {
            leaves.addAll(leavesOf(child));
        }
        return leaves.isEmpty() ? List.of(id) : leaves;
    }

    /** The display name of the test class (or nested class) the test belongs to. */
    private String groupOf(TestIdentifier id) {
        Optional<TestIdentifier> current = Optional.of(id);
        while (current.isPresent() && plan != null) {
            TestIdentifier t = current.get();
            if (t.getSource().filter(ClassSource.class::isInstance).isPresent()) {
                return t.getDisplayName();
            }
            current = plan.getParent(t);
        }
        return "(other)";
    }

    private static String trace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        List<String> lines = out.toString().lines().toList();
        if (lines.size() <= MAX_TRACE_LINES) {
            return String.join("\n", lines);
        }
        return String.join("\n", lines.subList(0, MAX_TRACE_LINES))
                + "\n\t... " + (lines.size() - MAX_TRACE_LINES) + " more lines";
    }

    // ---------------------------------------------------------------------------------------------
    // Settings

    /** The history folder; resolved here, inside the error handling, so a bad path is only a warning. */
    Path historyDir() {
        return Paths.get(dirSetting == null || dirSetting.isBlank() ? DEFAULT_DIR : dirSetting.trim());
    }

    static int parseKeep(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_KEEP;
        }
        try {
            int keep = Integer.parseInt(value.trim());
            if (keep >= 0) {
                return keep;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        System.err.println("[run history] WARNING: historyKeep=" + value + " isn't a whole number >= 0; keeping "
                + DEFAULT_KEEP + " runs.");
        return DEFAULT_KEEP;
    }

    // ---------------------------------------------------------------------------------------------
    // Saving a run

    /**
     * Writes the run's report under the next free number, adds it to runs.tsv, keeps the newest runs,
     * rebuilds the index, and only then deletes the reports of dropped runs.
     */
    Run save(Path dir, List<Result> runResults, LocalDateTime runStarted, long millis) throws IOException {
        Files.createDirectories(dir);
        List<Run> runs = readRuns(dir);
        Run run = writeReport(dir, runResults, runStarted, millis, nextNumber(dir, runs));
        runs.add(run);
        runs.sort(Comparator.comparingInt(Run::number));
        int cutoff = keep == 0 ? 0 : cutoff(dir, runs, keep);
        List<Run> kept = new ArrayList<>(runs.stream().filter(r -> r.number() >= cutoff).toList());
        Path report = dir.resolve(run.file());   // index.html and runs.tsv get the report's permissions
        writeRuns(dir, kept, report);
        replace(dir.resolve(INDEX_FILE), indexHtml(kept), report);
        if (cutoff > 0) {
            deleteReportsBelow(dir, cutoff);
        }
        deleteStaleTempFiles(dir);
        return run;
    }

    /**
     * Deletes temporary files of {@link #replace} left behind by a run that was killed mid-save, once they
     * are older than {@link #STALE_TEMP} (a run that is saving right now is never that slow). Best effort:
     * it runs after the run is saved, so a file that vanishes meanwhile (another run moved it into place) or
     * can't be deleted is left for the next run, and never turns a saved run into a warning.
     */
    static void deleteStaleTempFiles(Path dir) {
        long staleBefore = System.currentTimeMillis() - STALE_TEMP.toMillis();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                try {
                    if (TEMP_FILE.matcher(p.getFileName().toString()).matches() && Files.isRegularFile(p)
                            && Files.getLastModifiedTime(p).toMillis() < staleBefore) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException e) {
                    // gone or locked: the next run tries again
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // the folder can't be listed right now: the next run tries again
        }
    }

    /**
     * The oldest run number to keep: the {@code keep}-th newest of all runs, counting both runs.tsv and the
     * report files on disk (so lines lost from runs.tsv can't make it too new), or 0 if there aren't more
     * than {@code keep} runs.
     */
    static int cutoff(Path dir, List<Run> runs, int keep) throws IOException {
        TreeSet<Integer> numbers = new TreeSet<>(Comparator.reverseOrder());
        runs.forEach(r -> numbers.add(r.number()));
        for (Path p : reportFiles(dir)) {
            numberOf(p).ifPresent(numbers::add);
        }
        if (numbers.size() <= keep) {
            return 0;
        }
        return numbers.stream().skip(keep - 1L).findFirst().orElse(0);
    }

    private Run writeReport(Path dir, List<Result> runResults, LocalDateTime runStarted, long millis, int first)
            throws IOException {
        int passed = count(runResults, Status.PASSED);
        int failed = count(runResults, Status.FAILED);
        int aborted = count(runResults, Status.ABORTED);
        int skipped = count(runResults, Status.SKIPPED);
        String type = testFilter == null ? "full" : "filtered";
        for (int number = first; ; number++) {
            String file = String.format(Locale.ROOT, "run-%04d_%s%s.html", number, runStarted.format(FILE_STAMP),
                    testFilter == null ? "" : "_filtered");
            Run run = new Run(number, runStarted.format(SHOWN_STAMP), type, passed, failed, aborted, skipped, millis, file);
            try {
                Files.writeString(dir.resolve(file), reportHtml(run, runResults), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
                return run;
            } catch (FileAlreadyExistsException e) {
                // another run took this number; try the next one
            }
        }
    }

    private static int count(List<Result> runResults, Status status) {
        return (int) runResults.stream().filter(r -> r.status() == status).count();
    }

    /** One more than the highest number in runs.tsv or on a report file, so numbers never repeat. */
    static int nextNumber(Path dir, List<Run> runs) throws IOException {
        int max = runs.stream().mapToInt(Run::number).max().orElse(0);
        for (Path p : reportFiles(dir)) {
            max = Math.max(max, numberOf(p).orElse(0));
        }
        return max + 1;
    }

    static List<Run> readRuns(Path dir) throws IOException {
        Path file = dir.resolve(RUNS_FILE);
        List<Run> runs = new ArrayList<>();
        if (!Files.exists(file)) {
            return runs;
        }
        // Decoded leniently (invalid bytes become U+FFFD) so a damaged file only loses the damaged lines,
        // which the checks below drop, instead of stopping the history from being saved.
        for (String line : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList()) {
            String[] f = line.split("\t", -1);
            if (f.length != 9 || !REPORT_FILE.matcher(f[8]).matches()) {
                continue;   // a damaged line is dropped
            }
            try {
                runs.add(new Run(Integer.parseInt(f[0]), f[1], f[2], Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                        Integer.parseInt(f[5]), Integer.parseInt(f[6]), Long.parseLong(f[7]), f[8]));
            } catch (NumberFormatException e) {
                // a damaged line is dropped
            }
        }
        return runs;
    }

    private static void writeRuns(Path dir, List<Run> runs, Path permissionsFrom) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Run r : runs) {
            sb.append(r.number()).append('\t').append(r.started()).append('\t').append(r.type()).append('\t')
                    .append(r.passed()).append('\t').append(r.failed()).append('\t').append(r.aborted()).append('\t')
                    .append(r.skipped()).append('\t').append(r.millis()).append('\t').append(r.file()).append('\n');
        }
        replace(dir.resolve(RUNS_FILE), sb.toString(), permissionsFrom);
    }

    /** Deletes every report in the folder numbered below {@code cutoff}, whether runs.tsv lists it or not. */
    private static void deleteReportsBelow(Path dir, int cutoff) throws IOException {
        for (Path p : reportFiles(dir)) {
            OptionalInt n = numberOf(p);
            if (n.isPresent() && n.getAsInt() < cutoff) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** The report files directly in the folder (only plain names that match REPORT_FILE). */
    private static List<Path> reportFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> REPORT_FILE.matcher(p.getFileName().toString()).matches())
                    .filter(Files::isRegularFile).toList();
        }
    }

    private static OptionalInt numberOf(Path report) {
        Matcher m = REPORT_FILE.matcher(report.getFileName().toString());
        return m.matches() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    /**
     * Writes through a temporary file of its own (so two runs at once can't mix their writes) and moves it
     * into place, atomically where the file system allows it.
     */
    static void replace(Path target, String content, Path permissionsFrom) throws IOException {
        Path tmp = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            copyPermissions(permissionsFrom, tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * A temporary file is private to its owner (rw-------). Give it the permissions of a report written
     * normally (which follow the user's umask), so the index and runs.tsv are exactly as readable as the
     * reports they list; this also repairs files an earlier version wrote owner-only. Best effort: if the
     * permissions can't be read or set, the file keeps its own and the history is still saved.
     */
    private static void copyPermissions(Path from, Path tmp) {
        try {
            if (from != null && Files.getFileStore(tmp).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(from));
            }
        } catch (IOException | UnsupportedOperationException e) {
            System.err.println("[run history] WARNING: couldn't set the permissions of " + tmp.getFileName() + ": " + e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // HTML

    private static final String STYLE = """
            <style>
              body { font: 14px/1.45 -apple-system, "Segoe UI", Roboto, sans-serif; margin: 24px; color: #1f2328; }
              h1 { font-size: 22px; margin: 0 0 4px; } h2 { font-size: 16px; margin: 24px 0 8px; }
              .sub { color: #59636e; margin-bottom: 16px; }
              table { border-collapse: collapse; width: 100%; }
              th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid #d1d9e0; vertical-align: top; }
              th { background: #f6f8fa; font-weight: 600; }
              td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
              .badge { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 12px; font-weight: 600; color: #fff; }
              .PASSED { background: #1a7f37; } .FAILED { background: #cf222e; }
              .ABORTED { background: #9a6700; } .SKIPPED { background: #6e7781; }
              tr.bad td { background: #fff5f5; }
              .cards { display: flex; gap: 12px; flex-wrap: wrap; margin: 12px 0; }
              .card { border: 1px solid #d1d9e0; border-radius: 8px; padding: 8px 14px; min-width: 90px; }
              .card b { display: block; font-size: 20px; }
              .trend { display: flex; align-items: flex-end; gap: 3px; height: 40px; margin: 8px 0 4px; }
              .trend a { display: block; width: 14px; border-radius: 2px; }
              .trend a.ok { background: #1a7f37; } .trend a.ko { background: #cf222e; }
              details pre { white-space: pre-wrap; font-size: 12px; background: #f6f8fa; padding: 8px; border-radius: 6px; }
              a { color: #0969da; }
            </style>
            """;

    private String reportHtml(Run run, List<Result> runResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>Run #").append(run.number())
                .append(" – searchAPI tests</title>").append(STYLE).append("</head><body>\n");
        sb.append("<p><a href=\"").append(INDEX_FILE).append("\">← All runs</a></p>\n");
        sb.append("<h1>Run #").append(run.number()).append(' ')
                .append(badge(run.green() ? Status.PASSED : Status.FAILED)).append("</h1>\n");
        sb.append("<div class=\"sub\">").append(esc(run.started())).append(" · ").append(esc(run.type()))
                .append(testFilter == null ? "" : " (-Dtest=" + esc(testFilter) + ")")
                .append(" · ").append(duration(run.millis())).append("</div>\n");
        sb.append(cards(run));

        Map<String, List<Result>> groups = new LinkedHashMap<>();
        for (Result r : runResults) {
            groups.computeIfAbsent(r.group(), g -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Result>> g : groups.entrySet()) {
            long bad = g.getValue().stream().filter(r -> r.status() == Status.FAILED).count();
            sb.append("<h2>").append(esc(g.getKey())).append(" <span class=\"sub\">(").append(g.getValue().size())
                    .append(g.getValue().size() == 1 ? " result" : " results")
                    .append(bad > 0 ? ", " + bad + " failed" : "").append(")</span></h2>\n");
            sb.append("<table><tr><th>Result</th><th>Test</th><th class=\"num\">Time</th></tr>\n");
            for (Result r : g.getValue()) {
                sb.append("<tr").append(r.status() == Status.FAILED ? " class=\"bad\"" : "").append("><td>")
                        .append(badge(r.status())).append("</td><td>").append(esc(r.name()));
                if (!r.message().isEmpty()) {
                    // The first line as the summary; the whole message and the stack trace when opened.
                    String firstLine = r.message().lines().findFirst().orElse("");
                    sb.append("<details><summary>").append(esc(firstLine)).append("</summary><pre>")
                            .append(esc(r.trace().isEmpty() ? r.message() : r.trace())).append("</pre></details>");
                }
                sb.append("</td><td class=\"num\">").append(r.millis() < 0 ? "–" : duration(r.millis())).append("</td></tr>\n");
            }
            sb.append("</table>\n");
        }
        return sb.append("</body></html>\n").toString();
    }

    static String indexHtml(List<Run> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<title>searchAPI test run history</title>").append(STYLE).append("</head><body>\n");
        sb.append("<h1>searchAPI test run history</h1>\n");
        if (runs.isEmpty()) {
            return sb.append("<p>No runs yet.</p></body></html>\n").toString();
        }
        Run latest = runs.get(runs.size() - 1);
        long green = runs.stream().filter(Run::green).count();
        sb.append("<div class=\"sub\">").append(runs.size()).append(runs.size() == 1 ? " run" : " runs")
                .append(" kept (#").append(runs.get(0).number()).append(" to #").append(latest.number()).append("), ")
                .append(green).append(" without failures. Latest: <a href=\"").append(esc(latest.file()))
                .append("\">run #").append(latest.number()).append("</a>, ").append(esc(latest.started())).append(".</div>\n");

        // Trend, oldest to newest: one bar per run, green without failures and red with; its height is the
        // number of results in the run relative to the largest run, so partial runs stand out.
        int max = runs.stream().mapToInt(Run::total).max().orElse(1);
        sb.append("<div class=\"trend\" aria-label=\"Pass/fail trend, oldest to newest\">");
        for (Run r : runs) {
            int height = Math.max(6, (int) Math.round(40.0 * r.total() / Math.max(1, max)));
            sb.append("<a class=\"").append(r.green() ? "ok" : "ko").append("\" style=\"height:").append(height)
                    .append("px\" href=\"").append(esc(r.file())).append("\" title=\"Run #").append(r.number()).append(", ")
                    .append(esc(r.started())).append(": ").append(r.passed()).append('/').append(r.total())
                    .append(" passed\"></a>");
        }
        sb.append("</div><div class=\"sub\">Oldest → newest; bar height is the number of results in the run.</div>\n");

        sb.append("<table><tr><th class=\"num\">Run</th><th>Started</th><th>Result</th><th>Type</th>")
                .append("<th class=\"num\">Tests</th><th class=\"num\">Passed</th><th class=\"num\">Failed</th>")
                .append("<th class=\"num\">Aborted</th><th class=\"num\">Skipped</th><th class=\"num\">Pass rate</th>")
                .append("<th class=\"num\">Duration</th></tr>\n");
        for (int i = runs.size() - 1; i >= 0; i--) {
            Run r = runs.get(i);
            sb.append("<tr").append(r.green() ? "" : " class=\"bad\"").append("><td class=\"num\"><a href=\"")
                    .append(esc(r.file())).append("\">#").append(r.number()).append("</a></td><td>").append(esc(r.started()))
                    .append("</td><td>").append(badge(r.green() ? Status.PASSED : Status.FAILED)).append("</td><td>")
                    .append(esc(r.type())).append("</td><td class=\"num\">").append(r.total())
                    .append("</td><td class=\"num\">").append(r.passed()).append("</td><td class=\"num\">").append(r.failed())
                    .append("</td><td class=\"num\">").append(r.aborted()).append("</td><td class=\"num\">").append(r.skipped())
                    .append("</td><td class=\"num\">").append(passRate(r)).append("</td><td class=\"num\">")
                    .append(duration(r.millis())).append("</td></tr>\n");
        }
        return sb.append("</table>\n</body></html>\n").toString();
    }

    /** Passed out of the tests that ran to a pass or fail (skipped and aborted ones left out). */
    static String passRate(Run r) {
        int decided = r.passed() + r.failed();
        // Rounded down, so a run with any failure never shows 100%.
        return decided == 0 ? "–" : (int) Math.floor(100.0 * r.passed() / decided) + "%";
    }

    private static String cards(Run run) {
        return "<div class=\"cards\">" + card("Tests", run.total()) + card("Passed", run.passed())
                + card("Failed", run.failed()) + card("Aborted", run.aborted()) + card("Skipped", run.skipped()) + "</div>\n";
    }

    private static String card(String label, int value) {
        return "<div class=\"card\"><b>" + value + "</b>" + label + "</div>";
    }

    private static String badge(Status status) {
        return "<span class=\"badge " + status.name() + "\">" + status.name().toLowerCase(Locale.ROOT) + "</span>";
    }

    static String duration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        long seconds = Math.round(millis / 1000.0);
        return seconds < 60 ? seconds + " s" : (seconds / 60) + " min " + (seconds % 60) + " s";
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
