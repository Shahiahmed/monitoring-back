package com.example.monitoring.dto;

/**
 * Реальные метрики сервера, полученные по SSH.
 * Совместимо со старым monitoring-system-src: used/available из free, диск /dev/sda1 или /.
 */
public record ServerMetricsResponse(
        Long serverId,
        Double cpuPercent,
        Double memoryPercent,
        Double diskPercent,
        Long memoryUsedMb,
        Long memoryTotalMb,
        Long memoryAvailableMb,
        Long diskUsedGb,
        Long diskTotalGb,
        String error
) {
    public static ServerMetricsResponse error(Long serverId, String error) {
        return new ServerMetricsResponse(serverId, null, null, null, null, null, null, null, null, error);
    }

    public static ServerMetricsResponse ok(
            Long serverId,
            double cpu,
            double memoryPercent,
            double diskPercent,
            long memoryUsedMb,
            long memoryTotalMb,
            long memoryAvailableMb,
            long diskUsedGb,
            long diskTotalGb
    ) {
        return new ServerMetricsResponse(
                serverId,
                cpu,
                memoryPercent,
                diskPercent,
                memoryUsedMb,
                memoryTotalMb,
                memoryAvailableMb,
                diskUsedGb,
                diskTotalGb,
                null
        );
    }
}
