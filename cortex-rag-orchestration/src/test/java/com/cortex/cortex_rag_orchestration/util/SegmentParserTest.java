package com.cortex.cortex_rag_orchestration.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for SegmentParser. No Spring, no LLM, no network — we hand it
 * the exact string fragments the streaming API would produce and assert on the
 * segments it emits through the callback.
 */
class SegmentParserTest {

    // Collects everything the parser emits, so we can assert on it afterwards.
    private final List<AnswerSegmentDTO> emitted = new ArrayList<>();
    private final SegmentParser parser = new SegmentParser(new ObjectMapper(), emitted::add);

    @Test
    void reassemblesSegmentsSplitAcrossChunks() {
        // The full document is:
        // [{"text":"Docker uses namespaces","cites":[1]},{"text":" and
        // cgroups","cites":[1,3]}]
        // ...but delivered in nasty chunks that split mid-string, mid-key, and
        // mid-array.
        parser.feed("[{\"text\":\"Doc");
        parser.feed("ker uses ");
        parser.feed("namespaces\",\"ci");
        parser.feed("tes\":[1]},{\"text");
        parser.feed("\":\" and cgroups\",\"cites\":[");
        parser.feed("1,3]}]");

        assertThat(emitted).hasSize(2);
        assertThat(emitted.get(0).getText()).isEqualTo("Docker uses namespaces");
        assertThat(emitted.get(0).getCites()).containsExactly(1);
        assertThat(emitted.get(1).getText()).isEqualTo(" and cgroups");
        assertThat(emitted.get(1).getCites()).containsExactly(1, 3);
    }

    @Test
    void handlesSegmentWithEmptyCites() {
        parser.feed("[{\"text\":\"no source backs this\",\"cites\":[]}]");

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).getText()).isEqualTo("no source backs this");
        assertThat(emitted.get(0).getCites()).isEmpty();
    }

    @Test
    void handlesBracesAndEscapedQuotesInsideText() {
        // The text value contains a lone "}" and an escaped quote \"hi\". A brace
        // counter that ignores strings would miscount here and emit broken JSON.
        parser.feed("[{\"text\":\"press } and say \\\"hi\\\"\",\"cites\":[1]}]");

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).getText()).isEqualTo("press } and say \"hi\"");
        assertThat(emitted.get(0).getCites()).containsExactly(1);
    }

    @Test
    void parsesWholeDocumentDeliveredInOneFeed() {
        // Not every response is chunked — prove it also works arriving all at once,
        // and that the buffer is cleared between the two segments (no bleed-over).
        parser.feed("[{\"text\":\"first\",\"cites\":[1]},{\"text\":\"second\",\"cites\":[2]}]");

        assertThat(emitted).hasSize(2);
        assertThat(emitted.get(0).getText()).isEqualTo("first");
        assertThat(emitted.get(1).getText()).isEqualTo("second");
        assertThat(emitted.get(1).getCites()).containsExactly(2);
    }
}
