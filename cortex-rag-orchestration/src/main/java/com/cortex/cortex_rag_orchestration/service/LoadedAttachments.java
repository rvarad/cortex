package com.cortex.cortex_rag_orchestration.service;

import java.util.List;

/**
 * What {@link AttachmentLoader} managed to load.
 *
 * @param fileNames the files whose content is in {@code text}, in the order loaded — named in the
 *     block's header so the model can call them by name
 * @param text formatted chunks, ready to drop into the prompt; empty when nothing was loaded
 * @param notes one line per file that did not fit or was not available. These go to the model too —
 *     a file silently missing from the context is worse than one the model knows it cannot see.
 */
record LoadedAttachments(List<String> fileNames, String text, List<String> notes) {

  static final LoadedAttachments NONE = new LoadedAttachments(List.of(), "", List.of());

  /** True when the model was given nothing at all — no content and nothing to report. */
  boolean isEmpty() {
    return text.isBlank() && notes.isEmpty();
  }
}
