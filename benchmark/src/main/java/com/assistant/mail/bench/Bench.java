package com.assistant.mail.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** CLI entry: ./bench lint | check | run --system "<cmd>" | diff | show <id>[.<variant>] */
public class Bench {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path SCENARIOS = Path.of("scenarios");
    static final Path REFS = SCENARIOS.resolve("refs");
    static final Path RUNS = Path.of("runs");
    static final String CP = System.getProperty("java.class.path");

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "";
        switch (cmd) {
            case "lint" -> System.exit(lint() ? 0 : 1);
            case "check" -> System.exit(check());
            case "run" -> System.exit(run(flag(args, "--system", null), Integer.parseInt(flag(args, "--repeat", "1"))));
            case "diff" -> diff();
            case "compare" -> System.exit(compare(args.length > 2 ? RUNS.resolve(args[1]) : null,
                    args.length > 2 ? RUNS.resolve(args[2]) : null));
            case "show" -> show(args.length > 1 ? args[1] : null);
            default -> System.err.println("usage: ./bench lint | check | run --system \"<command>\" [--repeat N] | diff"
                    + " | compare [<runA> <runB>] | show <scenario>[.<variant>]");
        }
    }

    static String flag(String[] args, String name, String fallback) {
        for (int i = 1; i < args.length - 1; i++) if (args[i].equals(name)) return args[i + 1];
        return fallback;
    }

    static boolean lint() throws IOException {
        List<String> problems = Scenario.lint(SCENARIOS, list(REFS, ".jsonl"));
        problems.forEach(p -> System.out.println("lint  " + p));
        if (problems.isEmpty()) System.out.println("lint  ok");
        return problems.isEmpty();
    }

    /**
     * Proves the benchmark: replays every reference trace through the trace contract, scores it,
     * and compares the outcome with the one its file name promises. A mismatch is a gap.
     */
    static int check() throws Exception {
        if (!lint()) return 1;
        // the learning ruler itself (devpost/vision-notes.md §5): 2 drops in 4 steps, area 0.885
        double[] g = learning(new double[] {.88, .89, .87, .90, .88}), up = learning(new double[] {0, 1, 1});
        if (g[0] != 0.5 || Math.abs(g[1] - .885) > 1e-9 || Math.abs(g[2] - .4425) > 1e-9 || Math.abs(g[3]) > 1e-9
                || up[2] != .75 || learning(new double[] {1, 0, 1})[2] != .25 || learning(new double[] {.7, .7})[2] != .7)
            throw new AssertionError("learning score broken: " + Arrays.toString(g));
        Judge judge = new Judge(Files.createDirectories(RUNS).resolve("judge-cache.json"),
                JSON.readTree(SCENARIOS.resolve("tone-profiles.json").toFile()));
        String down = judge.preflight();
        if (down != null) {
            System.err.println("preflight: " + down);
            return 1;
        }
        Path run = RUNS.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Path traces = Files.createDirectories(run.resolve("traces"));
        String university = Files.readString(SCENARIOS.resolve("university.json"));
        ObjectNode results = JSON.createObjectNode().put("runId", run.getFileName().toString())
                .put("system", "replayer (reference traces)");
        ArrayNode scored = results.putArray("scenarios");
        int gaps = 0;
        System.out.printf("%n%-50s %-18s %-18s%n", "reference", "expected", "got");
        for (Path ref : list(REFS, ".jsonl")) {
            String name = ref.getFileName().toString().replace(".jsonl", "");
            String system = "java -cp '" + CP + "' com.assistant.mail.bench.Replayer '" + ref + "'";
            ObjectNode r = runScenario(system, SCENARIOS.resolve(name.substring(0, name.indexOf('.')) + ".json"),
                    name, traces, judge, university);
            r.put("expected", Scenario.expected(name));
            scored.add(r);
            String got = outcome(r);
            boolean ok = got.equals(r.path("expected").asString());
            if (!ok) gaps++;
            System.out.printf("%-50s %-18s %-18s %s%n", name, r.path("expected").asString(), got, ok ? "ok" : "GAP"
                    + (ok ? "" : "  " + Checks.texts(r.path("layers").path("output").path("reasons"))));
        }
        judge.save();
        Files.writeString(run.resolve("results.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        System.out.printf("%njudge: %d calls, %d cached, %d judge errors%n", judge.calls, judge.hits, judge.errors);
        System.out.printf("%d references, %s%nrun: %s%n", scored.size(),
                gaps == 0 ? "every outcome as expected" : gaps + " GAP(S): the benchmark misjudged these", run);
        return gaps == 0 ? 0 : 1;
    }

    /** Runs one system on one scenario under the trace contract, then scores and judges it. */
    static ObjectNode runScenario(String system, Path scenarioFile, String name, Path traces, Judge judge,
            String university) throws Exception {
        JsonNode scenario = JSON.readTree(scenarioFile.toFile());
        Path trace = traces.resolve(name + ".jsonl"), tools = traces.resolve(name + ".tools.jsonl");
        long start = System.currentTimeMillis();
        String error = runSystem(system, scenarioFile, trace, tools);
        long ms = System.currentTimeMillis() - start;
        ObjectNode r = error != null
                ? Checks.invalid(JSON.createObjectNode().put("id", scenario.path("id").asString())
                        .put("kind", scenario.path("kind").asString()), error)
                : Checks.score(scenario, readJsonl(trace), readJsonl(tools), university);
        if (error == null) judge.apply(scenario, readJsonl(trace), r);
        return r.put("trace", name).put("ms", ms);
    }

    /** Runs any system through every scenario, records the setup, and prints the verdict against the previous run. */
    static int run(String system, int repeat) throws Exception {
        if (system == null || repeat < 1) {
            System.err.println("usage: ./bench run --system \"<command>\" [--repeat N]");
            return 2;
        }
        if (!lint()) return 1;
        Judge judge = new Judge(Files.createDirectories(RUNS).resolve("judge-cache.json"),
                JSON.readTree(SCENARIOS.resolve("tone-profiles.json").toFile()));
        String down = judge.preflight();
        if (down != null) {
            System.err.println("preflight: " + down);
            return 1;
        }
        Path previous = latestSystemRun(null);
        Path run = RUNS.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Path traces = Files.createDirectories(run.resolve("traces"));
        String university = Files.readString(SCENARIOS.resolve("university.json"));
        ObjectNode results = JSON.createObjectNode().put("runId", run.getFileName().toString()).put("system", system);
        List<Path> scenarios = list(SCENARIOS, ".json").stream()
                .filter(p -> !Set.of("university.json", "tone-profiles.json").contains(p.getFileName().toString())).toList();
        StringBuilder all = new StringBuilder();
        for (Path p : list(SCENARIOS, ".json")) all.append(Files.readString(p));
        String dirty = sh("git", "status", "--porcelain");
        results.putObject("setup").put("commit", sh("git", "rev-parse", "--short", "HEAD"))
                .put("dirty", !dirty.isEmpty()).put("system", system)
                .put("judgeModel", Judge.MODEL).put("judgeDigest", judge.ollama.digest(Judge.MODEL)).put("seed", 42)
                .put("judgePromptVersion", Judge.PROMPT_VERSION).put("scenarioSetHash", Judge.sha256(all.toString())).put("repeat", repeat);
        ArrayNode scored = results.putArray("scenarios");
        for (int k = 1; k <= repeat; k++)
            for (Path s : scenarios) {
                String id = s.getFileName().toString().replace(".json", "");
                if (System.console() != null)
                    System.err.print("\r" + (scored.size() + 1) + "/" + scenarios.size() * repeat + " " + id + "          ");
                // repeat 1 keeps the plain id, so ./bench show <id> works; later repeats are <id>.r2, <id>.r3…
                scored.add(runScenario(system, s, k == 1 ? id : id + ".r" + k, traces, judge, university).put("repeat", k));
            }
        if (System.console() != null) System.err.print("\r" + " ".repeat(60) + "\r");
        judge.save();
        ObjectNode totals = results.putObject("totals");
        for (String o : List.of("successful", "failed", "invalid"))
            totals.put(o, count(scored, r -> r.path("outcome").asString().equals(o)));
        totals.put("harmful", count(scored, r -> r.path("harmful").asBoolean(false)));
        for (String l : Checks.LAYERS)
            totals.put(l, count(scored, r -> r.path("layers").path(l).path("pass").asBoolean(false)));
        results.set("scorecard", scorecard(scored));
        Files.writeString(run.resolve("results.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        verdict(results, previous == null ? null : JSON.readTree(previous.resolve("results.json").toFile()));
        printScorecards(results, null);
        System.out.printf("judge: %d calls, %d cached, %d judge errors%nrun: %s%n", judge.calls, judge.hits, judge.errors, run);
        return 0;
    }

    static void verdict(JsonNode now, JsonNode prev) {
        JsonNode t = now.path("totals");
        int n = now.path("scenarios").size();
        System.out.printf("%nrun %s   system: %s%n", now.path("runId").asString(), now.path("system").asString());
        if (prev == null) {
            System.out.printf("verdict     baseline (no previous run)%nsuccessful  %d/%d%n", t.path("successful").asInt(), n);
        } else {
            JsonNode p = prev.path("totals");
            List<String> differ = setupDiffers(now, prev);
            int d = Integer.compare(t.path("successful").asInt(), p.path("successful").asInt());
            if (d == 0) d = Integer.compare(layerSum(t), layerSum(p));
            String v = !differ.isEmpty() ? "not comparable (" + String.join(", ", differ) + " changed)"
                    : d > 0 ? color("▲ better", "32") : d < 0 ? color("▼ worse", "31") : "= no change";
            System.out.printf("verdict     %s   vs run %s%n", v, prev.path("runId").asString());
            System.out.printf("successful  %d/%d (was %d)%n", t.path("successful").asInt(), n, p.path("successful").asInt());
            System.out.printf("invalid     %d (was %d)%n", t.path("invalid").asInt(), p.path("invalid").asInt());
            for (String l : Checks.LAYERS)
                System.out.printf("  %-7s   %d pass (was %d)%n", l, t.path(l).asInt(), p.path(l).asInt());
        }
        if (t.path("harmful").asInt() > 0) System.out.println("harmful     " + t.path("harmful").asInt() + "  [HARMFUL]");
        System.out.println("detail: ./bench diff   ./bench compare   ./bench show <id>");
    }

    /** Setup fields that must match for two runs to be scored with the same ruler. The system may differ. */
    static List<String> setupDiffers(JsonNode a, JsonNode b) {
        return List.of("judgeModel", "judgeDigest", "judgePromptVersion", "scenarioSetHash", "repeat").stream()
                .filter(k -> !a.path("setup").path(k).equals(b.path("setup").path(k))).toList();
    }

    static final List<String> DIMS = List.of("task", "factuality", "tone", "relevance", "concise", "injection",
            "reliability", "learning", "latency");

    /**
     * Outcome-only scorecard: each dimension is a 0-1 pass rate over the executions it applies to,
     * the composite is their plain mean x 100. Invalid executions fail reliability, task and factuality.
     * With --repeat ≥ 2, trial k is repeat k and learning is scored per scenario, kind, dimension and overall;
     * the `learning` dimension is the mean over scenarios. With one trial it is absent from the composite.
     */
    static ObjectNode scorecard(JsonNode scored) {
        ObjectNode card = JSON.createObjectNode();
        Map<String, int[]> rates = new LinkedHashMap<>(); // dimension -> {pass, applicable}
        int trials = 1;
        for (JsonNode r : scored) trials = Math.max(trials, r.path("repeat").asInt(1));
        // one curve per scenario, kind and dimension, plus overall: {sum, count} per trial, results only
        Map<String, double[][]> byScenario = new TreeMap<>(), byKind = new TreeMap<>(), byDim = new LinkedHashMap<>();
        double[][] overall = new double[2][trials];
        Map<String, ObjectNode[]> checks = new TreeMap<>(); // scenario -> its result checks in each trial
        List<Long> ms = new ArrayList<>();
        for (JsonNode r : scored) {
            boolean invalid = r.path("outcome").asString().equals("invalid");
            ObjectNode d = (invalid ? JSON.createObjectNode().put("task", false).put("factuality", false)
                    : ((ObjectNode) r.path("dims")).deepCopy()).put("reliability", !invalid);
            int k = r.path("repeat").asInt(1) - 1;
            checks.computeIfAbsent(r.path("id").asString(), x -> new ObjectNode[overall[0].length])[k] = d;
            double pass = 0;
            for (var e : d.properties()) {
                boolean p = e.getValue().asBoolean();
                tally(rates, e.getKey(), p);
                point(byDim, e.getKey(), k, trials, p ? 1 : 0);
                if (p) pass++;
            }
            double score = pass / d.size();
            point(byScenario, r.path("id").asString(), k, trials, score);
            point(byKind, r.path("kind").asString(), k, trials, score);
            overall[0][k] += score;
            overall[1][k]++;
            ms.add(r.path("ms").asLong());
        }
        ms.sort(null);
        long p95 = ms.isEmpty() ? 0 : ms.get((int) Math.ceil(0.95 * ms.size()) - 1);
        ObjectNode detail = JSON.createObjectNode();
        if (trials >= 2) {
            double[] o = learning(curve(overall));
            ArrayNode t = detail.putArray("trials");
            for (double v : curve(overall)) t.add(r3(v));
            detail.put("overall", r3(o[2])).put("noDrop", r3(o[0])).put("area", r3(o[1])).put("gain", r3(o[3]));
            double individual = 0;
            for (var e : Map.of("byKind", byKind, "byDimension", byDim, "byScenario", byScenario).entrySet()) {
                ObjectNode level = detail.putObject(e.getKey());
                e.getValue().forEach((key, c) -> level.put(key, r3(learning(curve(c))[2])));
            }
            for (double[][] c : byScenario.values()) individual += learning(curve(c))[2];
            // every drop of a scenario: the step where it happened and the checks that passed before and failed after
            ArrayNode drops = detail.putArray("drops");
            byScenario.forEach((id, c) -> {
                double[] sc = curve(c);
                ObjectNode[] ch = checks.get(id);
                for (int step = 1; step < sc.length; step++) {
                    if (sc[step] >= sc[step - 1] || ch[step - 1] == null || ch[step] == null) continue;
                    ArrayNode lost = drops.addObject().put("scenario", id).put("from", step).put("to", step + 1)
                            .put("before", r3(sc[step - 1])).put("after", r3(sc[step])).putArray("lost");
                    for (var e : ch[step - 1].properties())
                        if (e.getValue().asBoolean() && !ch[step].path(e.getKey()).asBoolean(false)) lost.add(e.getKey());
                }
            });
            card.put("learning", individual / byScenario.size());
        }
        double sum = 0;
        int n = 0;
        for (String dim : DIMS) {
            if (dim.equals("learning")) {
                if (!card.has(dim)) continue;
                sum += card.path(dim).asDouble();
                card.put(dim, r3(card.path(dim).asDouble()));
            } else {
                int[] t = rates.getOrDefault(dim, new int[2]);
                double rate = dim.equals("latency") ? 1 / (1 + p95 / 60000.0) : t[1] == 0 ? 0 : (double) t[0] / t[1];
                card.put(dim, r3(rate));
                sum += rate;
            }
            n++;
        }
        card.put("p95Ms", p95).put("composite", Math.round(sum / n * 1000) / 10.0);
        if (trials >= 2) card.set("learningDetail", detail);
        return card;
    }

    static double r3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    static void point(Map<String, double[][]> curves, String key, int trial, int trials, double v) {
        double[][] c = curves.computeIfAbsent(key, x -> new double[2][trials]);
        c[0][trial] += v;
        c[1][trial]++;
    }

    static double[] curve(double[][] c) {
        double[] s = new double[c[0].length];
        for (int k = 0; k < s.length; k++) s[k] = c[1][k] == 0 ? 0 : c[0][k] / c[1][k];
        return s;
    }

    /**
     * Learning over trials of 0-1 scores (devpost/vision-notes.md §5). Returns {D, A, C, gain}:
     * D = 1 − drops/(T−1), a drop being any s[t] < s[t−1] (inconsistency); A = area under the curve / (T−1),
     * so a bad start, a small gain or a low plateau all cost; C = D × A; gain = last − first.
     * Ideal: perfect from trial 1, or rising to it fast and never dropping → 1.
     */
    static double[] learning(double[] s) {
        int drops = 0;
        double area = 0;
        for (int t = 1; t < s.length; t++) {
            if (s[t] < s[t - 1]) drops++;
            area += (s[t - 1] + s[t]) / 2;
        }
        double d = 1 - (double) drops / (s.length - 1), a = area / (s.length - 1);
        return new double[] {d, a, d * a, s[s.length - 1] - s[0]};
    }

    static void tally(Map<String, int[]> rates, String dim, boolean pass) {
        int[] t = rates.computeIfAbsent(dim, k -> new int[2]);
        if (pass) t[0]++;
        t[1]++;
    }

    /** Side-by-side scorecards of any two ./bench run results (default: the last two). */
    static int compare(Path a, Path b) throws IOException {
        if (a == null) {
            b = latestSystemRun(null);
            a = b == null ? null : latestSystemRun(b);
        }
        if (a == null || !Files.exists(a.resolve("results.json")) || !Files.exists(b.resolve("results.json"))) {
            System.err.println("compare needs two ./bench run results: ./bench compare <runA> <runB>");
            return 2;
        }
        JsonNode ra = JSON.readTree(a.resolve("results.json").toFile()), rb = JSON.readTree(b.resolve("results.json").toFile());
        if (!ra.has("scorecard") || !rb.has("scorecard")) {
            System.err.println("both runs need a scorecard (made by ./bench run)");
            return 2;
        }
        System.out.printf("A  %s   system: %s%nB  %s   system: %s%n", ra.path("runId").asString(), ra.path("system").asString(),
                rb.path("runId").asString(), rb.path("system").asString());
        List<String> differ = setupDiffers(ra, rb);
        if (!differ.isEmpty()) System.out.println("not comparable (" + String.join(", ", differ) + " differ)");
        printScorecards(rb, ra);
        return 0;
    }

    /** Prints a run's scorecard; with {@code other}, as B next to A with the difference. */
    static void printScorecards(JsonNode b, JsonNode a) {
        JsonNode cb = b.path("scorecard"), ca = a == null ? null : a.path("scorecard");
        System.out.printf("%n%-13s %7s%s%n", "scorecard", a == null ? "" : "A", a == null ? "" : String.format(" %7s %8s", "B", "B-A"));
        for (String dim : DIMS)
            if (cb.has(dim)) row(dim, ca == null || !ca.has(dim) ? null : ca.path(dim).asDouble(), cb.path(dim).asDouble(), "%7.3f");
        row("composite", ca == null ? null : ca.path("composite").asDouble(), cb.path("composite").asDouble(), "%7.1f");
        JsonNode l = cb.path("learningDetail");
        if (l.isObject()) {
            System.out.printf("%nlearning      trials %s  overall %.3f = noDrop %.3f × area %.3f   gain %+.3f%n", l.path("trials"),
                    l.path("overall").asDouble(), l.path("noDrop").asDouble(), l.path("area").asDouble(), l.path("gain").asDouble());
            for (String level : List.of("byKind", "byDimension")) {
                StringBuilder line = new StringBuilder();
                l.path(level).properties().forEach(e -> line.append(String.format("  %s %.3f", e.getKey(), e.getValue().asDouble())));
                System.out.printf("  %-11s%s%n", level, line);
            }
            StringBuilder weak = new StringBuilder();
            l.path("byScenario").properties().forEach(e -> {
                if (e.getValue().asDouble() < 1) weak.append(String.format("  %s %.3f", e.getKey(), e.getValue().asDouble()));
            });
            System.out.printf("  %-11s%s%n", "below 1", weak.isEmpty() ? "  none" : weak);
            JsonNode drops = l.path("drops");
            System.out.printf("  %-11s  %s%n", "drops", drops.isEmpty() ? "none" : drops.size() + "");
            for (JsonNode d : drops)
                System.out.println("    " + color(String.format("▼ %-22s t%d→t%d  %.3f → %.3f  lost: %s", d.path("scenario").asString(),
                        d.path("from").asInt(), d.path("to").asInt(), d.path("before").asDouble(), d.path("after").asDouble(),
                        String.join(", ", Checks.texts(d.path("lost")))), "31"));
        }
        System.out.printf("p95 latency   %s%n", a == null ? cb.path("p95Ms").asLong() / 1000.0 + " s"
                : ca.path("p95Ms").asLong() / 1000.0 + " s (A) vs " + cb.path("p95Ms").asLong() / 1000.0 + " s (B)");
    }

    static void row(String name, Double a, double b, String fmt) {
        if (a == null) {
            System.out.printf("%-13s " + fmt + "%n", name, b);
            return;
        }
        double d = b - a;
        String delta = Math.abs(d) < 1e-9 ? "=" : String.format(fmt.replace("%7", "%+7"), d) + (d > 0 ? " ▲" : " ▼");
        System.out.printf("%-13s " + fmt + " " + fmt + " %s%n", name, a, b, d > 0 ? color(delta, "32") : d < 0 ? color(delta, "31") : delta);
    }

    /** Which scenarios flipped per component between the latest system run and the one before it. */
    static void diff() throws IOException {
        Path now = latestSystemRun(null), prev = now == null ? null : latestSystemRun(now);
        if (prev == null) {
            System.err.println("diff needs two ./bench run results");
            return;
        }
        Map<String, JsonNode> before = new HashMap<>();
        for (JsonNode r : JSON.readTree(prev.resolve("results.json").toFile()).path("scenarios"))
            before.put(r.path("trace").asString(), r);
        System.out.printf("diff %s -> %s%n", prev.getFileName(), now.getFileName());
        Map<String, List<String>> flips = new LinkedHashMap<>();
        for (String c : List.of("planner/route", "tool calls", "planner/facts", "drafter", "summarizer", "whole run"))
            flips.put(c, new ArrayList<>());
        boolean judged = false;
        for (JsonNode r : JSON.readTree(now.resolve("results.json").toFile()).path("scenarios")) {
            JsonNode b = before.get(r.path("trace").asString());
            if (b == null) continue;
            boolean invNow = r.path("outcome").asString().equals("invalid"), invBefore = b.path("outcome").asString().equals("invalid");
            if (invNow || invBefore) {
                if (invNow != invBefore) flips.get("whole run").add(String.format("%s %-20s %s", invNow ? "▼" : "▲",
                        r.path("trace").asString(), invNow ? "now invalid: " + r.path("reason").asString() : "valid again"));
                continue;
            }
            for (String l : Checks.LAYERS) {
                boolean was = b.path("layers").path(l).path("pass").asBoolean(), is = r.path("layers").path(l).path("pass").asBoolean();
                if (was == is) continue;
                String c = switch (l) {
                    case "route" -> "planner/route";
                    case "tools" -> "tool calls";
                    case "facts" -> "planner/facts";
                    default -> r.path("kind").asString().equals("summarize") ? "summarizer" : "drafter";
                };
                List<String> why = Checks.texts(r.path("layers").path(l).path("reasons"));
                judged |= why.stream().anyMatch(w -> w.startsWith("judge "));
                flips.get(c).add(String.format("%s %-20s %s", is ? "▲ pass" : "▼ FAIL", r.path("trace").asString(),
                        is ? "" : String.join("; ", why)));
            }
        }
        for (var e : flips.entrySet()) {
            System.out.printf("%n%s  (%d flipped)%n", e.getKey(), e.getValue().size());
            e.getValue().forEach(f -> System.out.println("  " + f));
        }
        if (judged) System.out.println("\nnote: a lone judge flip may be judge noise (Ollama isn't bit-exact at seed 42).");
    }

    /** Newest run made by ./bench run (it has a setup), older than `before` when given. */
    static Path latestSystemRun(Path before) throws IOException {
        return list(RUNS, "").stream().filter(Files::isDirectory)
                .filter(p -> before == null || p.compareTo(before) < 0)
                .filter(p -> {
                    try {
                        return JSON.readTree(p.resolve("results.json").toFile()).has("setup");
                    } catch (Exception e) {
                        return false;
                    }
                }).max(Comparator.naturalOrder()).orElse(null);
    }

    static int count(JsonNode rs, java.util.function.Predicate<JsonNode> f) {
        int n = 0;
        for (JsonNode r : rs) if (f.test(r)) n++;
        return n;
    }

    static int layerSum(JsonNode totals) {
        return Checks.LAYERS.stream().mapToInt(l -> totals.path(l).asInt()).sum();
    }

    static String color(String s, String code) {
        return System.console() != null ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
    }

    static String sh(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes()).strip();
            return p.waitFor() == 0 ? out : "";
        } catch (Exception e) {
            return "";
        }
    }

    static String outcome(JsonNode r) {
        String o = r.path("outcome").asString();
        return o.equals("failed") ? "failed at " + r.path("firstFailingLayer").asString() : o;
    }

    /** Runs one system command under the trace contract. Returns null on success, else why it broke. */
    static String runSystem(String system, Path scenario, Path traceOut, Path toolLog) throws Exception {
        Files.deleteIfExists(toolLog);
        Files.createFile(toolLog);
        String mailbox = "java -cp '" + CP + "' com.assistant.mail.mailbox.MailboxServer '"
                + scenario.toAbsolutePath() + "' '" + toolLog.toAbsolutePath() + "'";
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", system)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.environment().put("SCENARIO", scenario.toAbsolutePath().toString());
        pb.environment().put("MAILBOX_CMD", mailbox);
        pb.environment().put("TRACE_OUT", traceOut.toAbsolutePath().toString());
        Process p = pb.start();
        int minutes = Integer.parseInt(System.getenv().getOrDefault("BENCH_MINUTES", "5"));
        if (!p.waitFor(minutes, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return "timeout after " + minutes + " minutes";
        }
        if (p.exitValue() != 0) return "system exited with " + p.exitValue();
        if (!Files.exists(traceOut)) return "no trace written to TRACE_OUT";
        return null;
    }

    static void show(String id) throws IOException {
        if (id == null) {
            System.err.println("usage: ./bench show <scenario>[.<variant>]");
            return;
        }
        Path run = latestRun();
        Path resultsFile = run.resolve("results.json");
        JsonNode results = Files.exists(resultsFile) ? JSON.readTree(resultsFile.toFile()) : JSON.missingNode();
        // ./bench run names traces by scenario id; ./bench check by reference (id.variant)
        String name = id.contains(".") || results.has("setup") ? id : id + ".good";
        Path trace = run.resolve("traces").resolve(name + ".jsonl");
        JsonNode r = JSON.missingNode();
        for (JsonNode x : results.path("scenarios"))
            if (x.path("trace").asString().equals(name)) r = x;
        if (r.isMissingNode() && !Files.exists(trace)) {
            System.err.println("no trace " + name + " in run " + run.getFileName());
            return;
        }
        String failing = r.path("firstFailingLayer").asString("");
        String failReasons = r.path("layers").path(failing).path("reasons").toString();
        System.out.println(name + "   (run " + run.getFileName() + ")");
        if (!r.isMissingNode()) {
            System.out.println("outcome: " + outcome(r) + (r.path("harmful").asBoolean(false) ? "  [HARMFUL]" : "")
                    + (r.has("expected") ? "   (expected: " + r.path("expected").asString() + ")" : ""));
            if (r.has("reason")) System.out.println("  " + r.path("reason").asString());
            for (String l : Checks.LAYERS) {
                JsonNode layer = r.path("layers").path(l);
                if (layer.isMissingNode()) continue;
                System.out.printf("  %-7s %s %s%n", l, layer.path("untraced").asBoolean(false) ? "not traced"
                        : layer.path("pass").asBoolean() ? "pass" : "FAIL",
                        String.join("; ", Checks.texts(layer.path("reasons"))));
            }
            for (JsonNode v : r.path("judge"))
                System.out.printf("  judge   %-9s %s  %s%n", v.path("criterion").asString(),
                        v.path("pass").asBoolean() ? "PASS" : "FAIL", v.path("reason").asString());
        }
        System.out.println();
        if (!Files.exists(trace)) System.out.println("(the system wrote no trace)");
        for (JsonNode e : Files.exists(trace) ? readJsonl(trace) : List.<JsonNode>of()) {
            String layer = e.path("layer").asString();
            // tool steps are marked only when the failure names them (e.g. a draft to the wrong person)
            boolean mark = layer.equals(failing) || ((layer + "s").equals(failing)
                    && (failReasons.contains(e.path("name").asString())
                        || e.path("name").asString().equals("create_draft") && failReasons.contains("draft")));
            System.out.printf("%2d  %-7s %-9s %-20s %s%s%n", e.path("step").asInt(), layer,
                    e.path("actor").asString(), e.path("name").asString(),
                    e.path("ms").asLong() > 0 ? e.path("ms").asLong() + " ms" : "", mark ? "   <<< first failing layer" : "");
            System.out.println("    in   " + clip(e.path("input")));
            if (e.has("error")) System.out.println("    ERR  " + e.path("error").asString());
            else System.out.println("    out  " + clip(e.path("output")));
        }
        System.out.println("\nMailbox tool log (recorded by the server, authoritative for the tool layer)");
        int i = 0;
        for (JsonNode c : readJsonl(run.resolve("traces").resolve(name + ".tools.jsonl")))
            System.out.printf("%2d  %-13s %s%s%n", ++i, c.path("tool").asString(), clip(c.path("args")),
                    c.has("error") ? "  ERR " + c.path("error").asString() : "");
    }

    static Path latestRun() throws IOException {
        return list(RUNS, "").stream().filter(Files::isDirectory).max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("no runs yet: run ./bench check first"));
    }

    static List<Path> list(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    static List<JsonNode> readJsonl(Path file) throws IOException {
        return Files.readAllLines(file).stream().filter(l -> !l.isBlank()).map(l -> (JsonNode) JSON.readTree(l))
                .toList();
    }

    static String clip(JsonNode node) {
        String s = node.isMissingNode() ? "" : node.toString();
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}
