package com.docs0.source;

import java.io.IOException;

/**
 * The source refused us and an admin has to act (reconnect, grant access). The message is
 * written for that admin and is shown as is in the sync log and on the Sources page.
 */
public class SourceAccessException extends IOException {

    public SourceAccessException(String message) {
        super(message);
    }
}
