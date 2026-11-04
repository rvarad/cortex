package com.cortex.cortex_common.kafka;

import com.cortex.cortex_common.model.PipelineEventEnum;

/**
 * A completion trigger could not be sent (ADR-0014, 2026-10-30): {@code CHUNKING_COMPLETE},
 * {@code NORMALISATION_COMPLETE}, {@code NORMALISATION_FAILED} or {@code EMBEDDING_COMPLETE}.
 *
 * <p>Each trigger is the last step of its unit of work, sent after the data it announces is saved.
 * So when it is lost, the work is done and only the signal to run the completion check is missing.
 * A boundary logs this once, but must not fail the file for it: the sweep runs the completion
 * check again later. Its own type, so a boundary can tell it from a real error.
 */
public class CompletionTriggerLostException extends RuntimeException {

  public CompletionTriggerLostException(PipelineEventEnum trigger, Throwable cause) {
    super("Completion trigger " + trigger + " not sent", cause);
  }
}
