package com.stockflow.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

public final class DashboardDtos {
    private DashboardDtos() {}
    @Schema(name = "DashboardSummary")
    public record Summary(long activeProductCount, long lowStockCount, long draftOrderCount,
            @Schema(type = "string", example = "1350.00") String confirmedOrderValue, String currency) {}
}
