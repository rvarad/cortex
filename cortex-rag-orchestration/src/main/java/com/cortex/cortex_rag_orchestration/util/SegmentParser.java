package com.cortex.cortex_rag_orchestration.util;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Consumer;

public class SegmentParser {

    private final StringBuilder buffer = new StringBuilder();

    private final ObjectMapper objectMapper;

    private final Consumer<AnswerSegmentDTO> onSegmentComplete;

    private int depth = 0;

    // Are we currently inside a "..." text value? Must be a field, not a local:
    // a string can be split across two feed() calls.
    private boolean inString = false;

    // Was the previous char a backslash inside a string? If so, the next char is
    // just a literal (like the " in \"), not something special.
    private boolean escape = false;

    public SegmentParser(ObjectMapper objectMapper, Consumer<AnswerSegmentDTO> onSegmentComplete) {
        this.objectMapper = objectMapper;
        this.onSegmentComplete = onSegmentComplete;
    }

    public void feed(String piece) {
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);

            // 1. Previous char was a backslash inside a string, so this char is just
            //    a literal (e.g. the " in \"). Keep it and stop treating it specially.
            if (escape) {
                buffer.append(c);
                escape = false;
                continue;
            }

            // 2. A backslash inside a string starts an escape for the next char.
            if (inString && c == '\\') {
                buffer.append(c);
                escape = true;
                continue;
            }

            // 3. A quote flips us into or out of a string.
            if (c == '"') {
                inString = !inString;
                buffer.append(c);
                continue;
            }

            // 4. Inside a string, a { or } is just text — never count it as structure.
            if (inString) {
                buffer.append(c);
                continue;
            }

            // 5. Outside a string: the real structure. Count braces and cut segments.
            if (c == '{') {
                depth++;
                buffer.append(c);
            } else if (c == '}') {
                buffer.append(c);
                depth--;
                if (depth == 0) {
                    try {
                        AnswerSegmentDTO segment = objectMapper.readValue(buffer.toString(), AnswerSegmentDTO.class);
                        onSegmentComplete.accept(segment);
                        buffer.setLength(0);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse segment", e);
                    }
                }
            } else if (depth > 0) {
                buffer.append(c);
            }
        }
    }
}
