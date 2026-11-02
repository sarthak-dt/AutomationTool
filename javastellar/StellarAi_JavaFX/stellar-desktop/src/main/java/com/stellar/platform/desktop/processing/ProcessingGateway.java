package com.stellar.platform.desktop.processing;

import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.processing.ProcessingProgressListener;

import java.util.List;
import java.util.function.Consumer;

/**
 * Desktop processing entry point. LOCAL runs in-process; REMOTE uses HTTP process APIs.
 */
public interface ProcessingGateway {

    void cancel();

    LocalProcessingGateway.GatewayResult process(
            WorkspaceFile measurement,
            WorkspaceFile definition,
            int batchSize,
            SignalPreviewBuffer previewBuffer,
            ProcessingProgressListener progressListener,
            Consumer<List<CanonicalSignal>> previewBatchListener,
            ProcessingSelection selection
    );
}
