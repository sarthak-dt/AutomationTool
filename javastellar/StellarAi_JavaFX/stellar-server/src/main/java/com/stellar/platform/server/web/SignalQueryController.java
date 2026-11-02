package com.stellar.platform.server.web;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.dto.SignalCatalogPageDto;
import com.stellar.platform.api.dto.SignalCatalogQuery;
import com.stellar.platform.api.dto.SignalSamplePageDto;
import com.stellar.platform.api.dto.SignalStatsResultDto;
import com.stellar.platform.api.dto.SignalViewportRequestDto;
import com.stellar.platform.api.dto.SignalViewportResultDto;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@Profile("!decoder")
@RequestMapping("/api/v1/projects/{projectId}/signals")
public class SignalQueryController {

    private final StellarPlatformClient client;

    public SignalQueryController(StellarPlatformClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @GetMapping("/catalog")
    public SignalCatalogPageDto catalog(
            @PathVariable("projectId") String projectId,
            @RequestParam(name = "q", required = false, defaultValue = "") String query,
            @RequestParam(name = "sourceType", required = false, defaultValue = "") String sourceType,
            @RequestParam(name = "sourceFile", required = false, defaultValue = "") String sourceFile,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "200") int limit
    ) {
        return client.signals().catalog(projectId, new SignalCatalogQuery(query, sourceType, sourceFile, offset, limit));
    }

    @GetMapping("/{signalKey}/samples")
    public SignalSamplePageDto samples(
            @PathVariable("projectId") String projectId,
            @PathVariable("signalKey") String signalKey,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "1000") int limit
    ) {
        return client.signals().samples(projectId, signalKey, offset, limit);
    }

    @GetMapping("/{signalKey}/range")
    public SignalSamplePageDto range(
            @PathVariable("projectId") String projectId,
            @PathVariable("signalKey") String signalKey,
            @RequestParam("from") long fromNanos,
            @RequestParam("to") long toNanos,
            @RequestParam(name = "limit", defaultValue = "1000") int limit
    ) {
        return client.signals().range(projectId, signalKey, fromNanos, toNanos, limit);
    }

    @PostMapping("/viewport")
    public SignalViewportResultDto viewport(
            @PathVariable("projectId") String projectId,
            @RequestBody SignalViewportRequestDto request
    ) {
        return client.signals().viewport(projectId, request);
    }

    @PostMapping("/viewport-stats")
    public SignalStatsResultDto viewportStats(
            @PathVariable("projectId") String projectId,
            @RequestBody SignalViewportRequestDto request
    ) {
        return client.signals().viewportStats(projectId, request);
    }
}
