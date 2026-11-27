package com.cortex.cortex_common.dto;

import java.util.UUID;

/**
 * A file the user attached to a message, as the reloaded chat draws it.
 *
 * @param fileDisplayName null when the file no longer exists. The id is kept regardless — the
 *     history should still say a file was attached here after it is gone.
 */
public record AttachedFileDTO(UUID fileId, String fileDisplayName) {
}
