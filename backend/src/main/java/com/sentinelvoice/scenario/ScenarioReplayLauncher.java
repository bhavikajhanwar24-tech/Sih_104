package com.sentinelvoice.scenario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Starts {@code gateway/replay_audio.py} so fixture scenarios feed real PCM into
 * ml-engine (not canned risk numbers).
 */
@Service
public class ScenarioReplayLauncher {

    private static final Logger log = LoggerFactory.getLogger(ScenarioReplayLauncher.class);

    private final ConcurrentHashMap<String, Process> active = new ConcurrentHashMap<>();

    @Value("${sentinelvoice.scenarios.python:}")
    private String pythonOverride;

    @Value("${sentinelvoice.scenarios.repo-root:}")
    private String repoRootOverride;

    /**
     * Ensure WAV exists (generate placeholder if missing), then stream into ingest WS.
     *
     * @return true if a replay process was started
     */
    public boolean startReplay(String sessionId, String wavRelativePath, String ingestWsUrl, String channelProfile) {
        if (sessionId == null || sessionId.isBlank() || wavRelativePath == null || wavRelativePath.isBlank()) {
            return false;
        }
        stopReplay(sessionId);

        Path root = resolveRepoRoot();
        Path wav = root.resolve(wavRelativePath).normalize();
        if (!wav.startsWith(root.normalize())) {
            throw new IllegalArgumentException("audio path escapes repo root: " + wavRelativePath);
        }

        Path python = resolvePython(root);
        List<String> cmd = new ArrayList<>();
        cmd.add(python.toString());
        cmd.add(root.resolve("gateway/replay_audio.py").toString());
        if (!Files.isRegularFile(wav)) {
            cmd.add("--generate-placeholder");
            cmd.add("--duration");
            cmd.add("45");
        }
        cmd.add("--wav");
        cmd.add(wav.toString());
        cmd.add("--session");
        cmd.add(sessionId);
        cmd.add("--ws");
        cmd.add(ingestWsUrl);
        if (channelProfile != null && !channelProfile.isBlank()) {
            cmd.add("--channel");
            cmd.add(channelProfile);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            active.put(sessionId, proc);
            Thread.ofVirtual().name("scenario-replay-" + sessionId).start(() -> drain(sessionId, proc));
            log.info(
                    "scenario_replay_started sessionId={} wav={} python={} cmdSize={}",
                    sessionId,
                    wav,
                    python,
                    cmd.size()
            );
            return true;
        } catch (Exception ex) {
            log.error("scenario_replay_failed sessionId={} err={}", sessionId, ex.toString());
            return false;
        }
    }

    public void stopReplay(String sessionId) {
        Process prev = active.remove(sessionId);
        if (prev == null) {
            return;
        }
        prev.destroy();
        try {
            prev.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (prev.isAlive()) {
            prev.destroyForcibly();
        }
    }

    public Map<String, Object> status(String sessionId) {
        Process p = active.get(sessionId);
        boolean alive = p != null && p.isAlive();
        return Map.of("sessionId", sessionId, "replaying", alive);
    }

    private void drain(String sessionId, Process proc) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("replay[{}] {}", sessionId, line);
            }
        } catch (Exception ex) {
            log.debug("replay_drain_ended sessionId={} err={}", sessionId, ex.toString());
        } finally {
            active.remove(sessionId, proc);
            int code = proc.isAlive() ? -1 : proc.exitValue();
            log.info("scenario_replay_ended sessionId={} exit={}", sessionId, code);
        }
    }

    private Path resolveRepoRoot() {
        if (repoRootOverride != null && !repoRootOverride.isBlank()) {
            return Path.of(repoRootOverride).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("gateway/replay_audio.py"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("gateway/replay_audio.py"))) {
            return parent;
        }
        return cwd;
    }

    private Path resolvePython(Path root) {
        if (pythonOverride != null && !pythonOverride.isBlank()) {
            return Path.of(pythonOverride);
        }
        Path venvWin = root.resolve("ml-engine/.venv/Scripts/python.exe");
        if (Files.isRegularFile(venvWin)) {
            return venvWin;
        }
        Path venvUnix = root.resolve("ml-engine/.venv/bin/python");
        if (Files.isRegularFile(venvUnix)) {
            return venvUnix;
        }
        return Path.of("python");
    }
}
