package com.docs0.agent;

import com.docs0.action.PendingAction;
import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.ProgressListener;
import com.docs0.agent.LlmClient.ToolSpec;
import java.util.List;
import java.util.Map;

/**
 * The tools beyond reading the knowledge base: look up where an item could be filed, and draft
 * one. Which of them exist depends on what is connected for the tenant.
 */
interface ActionTools {

    /** The tools to offer the model for this tenant right now. */
    List<ToolSpec> specs(long tenantId);

    boolean handles(String toolName);

    /**
     * Runs one of this tool set's tools. A tool that drafts something adds the draft to
     * {@code drafts}; it writes nothing to Linear or Trello.
     */
    KnowledgeTools.Output run(String name, Map<String, Object> input, AgentRequest request,
            ProgressListener progress, List<PendingAction> drafts);

    /** No extra tools. */
    ActionTools NONE = new ActionTools() {
        @Override
        public List<ToolSpec> specs(long tenantId) {
            return List.of();
        }

        @Override
        public boolean handles(String toolName) {
            return false;
        }

        @Override
        public KnowledgeTools.Output run(String name, Map<String, Object> input, AgentRequest request,
                ProgressListener progress, List<PendingAction> drafts) {
            return KnowledgeTools.Output.error("There is no tool named \"" + name + "\".");
        }
    };
}
