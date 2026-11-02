package com.stellar.platform.processing.graph;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

/** Runs viewport work off the JavaFX thread and suppresses stale results when the user zooms/pans again. */
public final class LatestViewportCoordinator {
    private final GraphViewportEngine engine;
    private final Executor executor;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();

    public LatestViewportCoordinator(GraphViewportEngine engine, Executor executor) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public CompletableFuture<List<GraphRenderSeries>> submit(
            GraphViewportRequest request,
            Consumer<List<GraphRenderSeries>> latestResultConsumer
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(latestResultConsumer, "latestResultConsumer");
        long mine = generation.incrementAndGet();
        CompletableFuture<?> previous = active.getAndSet(null);
        if (previous != null) previous.cancel(true);
        CompletableFuture<List<GraphRenderSeries>> future = CompletableFuture.supplyAsync(
                () -> engine.render(request, () -> generation.get() == mine), executor)
                .thenApply(result -> {
                    if (generation.get() == mine) latestResultConsumer.accept(result);
                    return result;
                });
        active.set(future);
        future.whenComplete((ignored, failure) -> active.compareAndSet(future, null));
        return future;
    }

    public void invalidate() {
        generation.incrementAndGet();
        CompletableFuture<?> previous = active.getAndSet(null);
        if (previous != null) previous.cancel(true);
    }
}
