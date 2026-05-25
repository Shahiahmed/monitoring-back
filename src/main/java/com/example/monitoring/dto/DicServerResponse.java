package com.example.monitoring.dto;

public record DicServerResponse(
        Long id,
        Boolean active,
        String description,
        String ip,
        Long envId,
        String envNameRu
) {
}
