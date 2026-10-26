package com.stockflow.dashboard;

import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.stockflow.dashboard.DashboardDtos.Summary;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard")
@SecurityRequirement(name = "bearerAuth")
public class DashboardController {
    private final DashboardService service;
    public DashboardController(DashboardService service) { this.service = service; }

    @GetMapping("/summary")
    @Operation(summary = "Read current business metrics (admin or staff)", description = "Low stock includes only active products at/below threshold. Draft count includes all DRAFT orders. Value sums only currently CONFIRMED orders (not collected revenue); cancellation removes value. One SQL statement provides a consistent summary snapshot. Mixed/unexpected confirmed currencies return 409 CURRENCY_MISMATCH rather than adding unlike amounts.")
    public Summary summary() { return service.summary(); }
}
