package com.cortex.cortex_rag_orchestration.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Consumer;

/**
 * Cuts a half-arrived JSON stream into whole objects, emitting each one as its
 * closing brace
 * arrives.
 *
 * <p>
 * Generic over the segment type because the two chat paths carry different
 * cites: the original
 * path streams {@code AnswerSegmentDTO} (integer cites), the agent streams its
 * own raw segment
 * (chunk-id cites, numbered later).
 */
public class SegmentParser<T> {

  private final StringBuilder buffer = new StringBuilder();

  private final ObjectMapper objectMapper;

  private final Class<T> type;

  private final Consumer<T> onSegmentComplete;

  private int depth = 0;

  // Are we currently inside a "..." text value? Must be a field, not a local:
  // a string can be split across two feed() calls.
  private boolean inString = false;

  // Was the previous char a backslash inside a string? If so, the next char is
  // just a literal (like the " in \"), not something special.
  private boolean escape = false;

  public SegmentParser(ObjectMapper objectMapper, Class<T> type, Consumer<T> onSegmentComplete) {
    this.objectMapper = objectMapper;
    this.type = type;
    this.onSegmentComplete = onSegmentComplete;
  }

  public void feed(String piece) {
    for (int i = 0; i < piece.length(); i++) {
      char c = piece.charAt(i);

      // 1. Previous char was a backslash inside a string, so this char is just
      // a literal (e.g. the " in \"). Keep it and stop treating it specially.
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
            T segment = objectMapper.readValue(buffer.toString(), type);
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
