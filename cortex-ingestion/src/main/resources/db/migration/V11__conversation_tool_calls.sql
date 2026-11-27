-- V11__conversation_tool_calls.sql
-- What the model did to produce an answer: the tools it called, in order, with their arguments.
-- Filled on ASSISTANT rows only; always '[]' on USER rows.
--
-- Each entry: {"round": 1, "name": "search_library", "args": {"query": "..."}}. "round" keeps
-- calls made together (parallel, in one round) apart from calls made one after another, so replay
-- can rebuild the turn the way it happened.
--
-- Results are deliberately not stored (CHAT-AGENT-PLAN F3): replay shows each old call with a
-- placeholder in place of its result, and if a result is needed again the tool runs again. Without
-- this column the model sees only the words it said, never what it did — so it cannot explain an
-- earlier answer ("why did you say that?") and goes searching the library for the reason.

ALTER TABLE conversation_message
    ADD COLUMN tool_calls jsonb NOT NULL DEFAULT '[]';
