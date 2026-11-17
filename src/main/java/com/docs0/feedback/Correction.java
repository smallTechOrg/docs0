package com.docs0.feedback;

import java.time.Instant;

/** A correction of one of docs0's answers, as stored. {@code editedText} is null unless an admin reworded it. */
public record Correction(
        long id,
        Long interactionId,
        String authorSlackId,
        String authorName,
        String question,
        String originalAnswer,
        String correctionText,
        String editedText,
        Status status,
        boolean fromDm,
        Instant createdAt,
        Instant reviewedAt) {

    public enum Status { PENDING, APPROVED, REJECTED }

    /** What docs0 would tell the model: the edited text if there is one, else the extracted fact. */
    public String fact() {
        return editedText != null && !editedText.isBlank() ? editedText : correctionText;
    }
}
