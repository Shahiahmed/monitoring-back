package com.example.monitoring.service;

import com.example.monitoring.dto.ServerMetricsResponse;
import com.example.monitoring.entity.DicServer;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Получение метрик (CPU, память, диск) с серверов по SSH.
 */
@Service
public class SshMetricsService {

    private static final Logger log = LoggerFactory.getLogger(SshMetricsService.class);

    private static final Pattern CPU_ID_PATTERN = Pattern.compile("(\\d+\\.?\\d*)\\s*id");
    private static final int DEFAULT_SSH_PORT = 22;

    @Value("${monitoring.ssh.user:monitoringapp}")
    private String sshUser;

    @Value("${monitoring.ssh.password:Qwerty123}")
    private String sshPassword;

    @Value("${monitoring.ssh.port:22}")
    private int sshPort;

    @Value("${monitoring.ssh.timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${monitoring.ssh.cache-seconds:60}")
    private int cacheSeconds;

    private final Map<Long, CachedMetrics> cache = new ConcurrentHashMap<>();

    public List<ServerMetricsResponse> fetchMetrics(List<DicServer> servers, boolean forceRefresh) {
        return servers.stream()
                .map(s -> fetchMetricsForServer(s, forceRefresh))
                .toList();
    }

    public ServerMetricsResponse fetchMetricsForServer(DicServer server, boolean forceRefresh) {
        Long id = server.getId();
        String ip = server.getIp();
        if (ip == null || ip.isBlank()) {
            return ServerMetricsResponse.error(id, "IP не задан");
        }

        if (!forceRefresh) {
            CachedMetrics cached = cache.get(id);
            if (cached != null && !cached.isExpired(cacheSeconds)) {
                return cached.response;
            }
        }

        try {
            ServerMetricsResponse response = doFetch(id, ip);
            if (response.error() == null) {
                cache.put(id, new CachedMetrics(response));
            }
            return response;
        } catch (Exception e) {
            log.warn("SSH к {} (id={}): {}", ip, id, e.getMessage());
            return ServerMetricsResponse.error(id, e.getMessage());
        }
    }

    private ServerMetricsResponse doFetch(Long serverId, String ip) throws IOException {
        try (SSHClient ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            ssh.setConnectTimeout(timeoutSeconds * 1000);
            ssh.connect(ip, sshPort);
            ssh.authPassword(sshUser, sshPassword);

            double cpu = parseCpu(exec(ssh, "top -bn1 2>/dev/null | grep -E 'Cpu\\(s\\)|%Cpu\\(s\\)' | head -1"));

            // Как в старом monitoring-system-src: used ($3) и available ($7)
            String memRaw = exec(ssh, "free -m 2>/dev/null | grep Mem");
            MemoryStats mem = parseMemoryUsedAvailable(memRaw);

            // Всегда берём корень / — на LVM-серверах /dev/sda1 это /boot (470M), а не корень
            String diskRaw = exec(ssh, "df -h / 2>/dev/null | tail -1");
            DiskStats disk = parseDiskDetailed(diskRaw);

            return ServerMetricsResponse.ok(
                    serverId,
                    cpu,
                    mem.percent,
                    disk.percent,
                    mem.usedMb,
                    mem.totalMb,
                    mem.availableMb,
                    disk.usedGb,
                    disk.totalGb
            );
        }
    }

    private String exec(SSHClient ssh, String command) throws IOException {
        try (var session = ssh.startSession()) {
            var cmd = session.exec(command);
            cmd.join(timeoutSeconds, TimeUnit.SECONDS);
            return new String(cmd.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private double parseCpu(String output) {
        if (output == null || output.isEmpty()) return 0;
        Matcher m = CPU_ID_PATTERN.matcher(output);
        if (m.find()) {
            double id = Double.parseDouble(m.group(1));
            return Math.max(0, Math.min(100, 100 - id));
        }
        return 0;
    }

    /**
     * Парсинг как в старом monitoring-system-src: used ($3), available ($7).
     * Формат free -m: Mem total used free shared buff/cache available
     */
    private MemoryStats parseMemoryUsedAvailable(String output) {
        if (output == null || output.isEmpty()) return new MemoryStats(0, 0, 0, 0);
        try {
            String[] parts = output.trim().split("\\s+");
            long total = Long.parseLong(parts[1]);
            long used = Long.parseLong(parts[2]);
            long available = parts.length >= 7 ? Long.parseLong(parts[6]) : (total - used);
            double percent = total > 0 ? (used * 100.0) / total : 0;
            return new MemoryStats(
                    Math.max(0, Math.min(100, percent)),
                    used,
                    total,
                    available
            );
        } catch (Exception e) {
            return new MemoryStats(0, 0, 0, 0);
        }
    }

    /**
     * Парсинг диска. Поддерживает:
     * - df -h -m (старый формат): колонки used, avail в MB
     * - df -BG (новый формат): Size, Used в GB
     */
    private DiskStats parseDiskDetailed(String output) {
        if (output == null || output.isEmpty()) return new DiskStats(0, 0, 0);
        try {
            String[] parts = output.trim().split("\\s+");
            if (parts.length < 4) return new DiskStats(0, 0, 0);

            // df -h -m: 1K-blocks used avail (числа в MB)
            // df -BG: Size Used Avail (с суффиксом G)
            String usedStr = parts[2];
            String availStr = parts.length >= 4 ? parts[3] : "0";

            long usedMb = parseMbOrGb(usedStr);
            long availMb = parseMbOrGb(availStr);
            long totalMb = usedMb + availMb;
            long usedGb = (usedMb + 512) / 1024;
            long totalGb = (totalMb + 512) / 1024;
            if (totalGb < 1) totalGb = 1;
            double percent = totalMb > 0 ? (usedMb * 100.0) / totalMb : 0;
            return new DiskStats(
                    Math.max(0, Math.min(100, percent)),
                    usedGb,
                    totalGb
            );
        } catch (Exception e) {
            return new DiskStats(0, 0, 0);
        }
    }

    private long parseMbOrGb(String value) {
        String v = value.trim().toUpperCase().replace(",", ".");
        boolean isGb = v.endsWith("G");
        if (v.endsWith("G") || v.endsWith("M")) {
            v = v.substring(0, v.length() - 1);
        }
        try {
            long num = (long) Double.parseDouble(v);
            return isGb ? num * 1024 : num;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static class CachedMetrics {
        final ServerMetricsResponse response;
        final long timestamp = System.currentTimeMillis();

        CachedMetrics(ServerMetricsResponse response) {
            this.response = response;
        }

        boolean isExpired(int cacheSeconds) {
            return (System.currentTimeMillis() - timestamp) > cacheSeconds * 1000L;
        }
    }

    private record MemoryStats(double percent, long usedMb, long totalMb, long availableMb) {}

    private record DiskStats(double percent, long usedGb, long totalGb) {}
}
