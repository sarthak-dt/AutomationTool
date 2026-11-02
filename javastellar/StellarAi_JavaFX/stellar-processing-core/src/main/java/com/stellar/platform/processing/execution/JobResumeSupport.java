package com.stellar.platform.processing.execution;

import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.parser.spi.ParseCheckpointListener;
import com.stellar.platform.parser.spi.ParserResumeCursor;

import java.util.Optional;

/**
 * Optional mid-file resume hooks. Implemented by the worker (storage-aware). Processing-core
 * stays format-agnostic and never inspects MF4 internals.
 */
public interface JobResumeSupport {

    JobResumeSupport NONE = new JobResumeSupport() {
    };

    default Optional<ParserResumeCursor> cursorFor(ProcessingJob job) {
        return Optional.empty();
    }

    default ParseCheckpointListener listenerFor(ProcessingJob job) {
        return ParseCheckpointListener.NONE;
    }
}
