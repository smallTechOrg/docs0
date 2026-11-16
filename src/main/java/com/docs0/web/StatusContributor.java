package com.docs0.web;

/** Implemented by each integration (Slack, Anthropic, Drive) to report its state on the status page. */
public interface StatusContributor {

    ComponentStatus status();

    /** UP: working. OFF: not configured (expected, not an error). DOWN: configured but failing. */
    enum State { UP, OFF, DOWN }

    record ComponentStatus(String name, State state, String detail) {

        public static ComponentStatus up(String name, String detail) {
            return new ComponentStatus(name, State.UP, detail);
        }

        public static ComponentStatus off(String name, String detail) {
            return new ComponentStatus(name, State.OFF, detail);
        }

        public static ComponentStatus down(String name, String detail) {
            return new ComponentStatus(name, State.DOWN, detail);
        }
    }
}
