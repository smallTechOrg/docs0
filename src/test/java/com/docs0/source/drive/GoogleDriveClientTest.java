package com.docs0.source.drive;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.config.Docs0Properties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Only the failure paths that never reach the network. */
class GoogleDriveClientTest {

    @TempDir
    Path tmp;

    @Test
    void missingCredentialsFileFailsOnFirstUseNotOnConstruction() {
        GoogleDriveClient client = client(tmp.resolve("missing.json").toString());

        assertThatThrownBy(() -> client.listFiles(List.of())).isInstanceOf(IOException.class);
    }

    @Test
    void credentialsFileThatIsNotAServiceAccountKeyFailsOnFirstUse() throws IOException {
        Path bad = Files.writeString(tmp.resolve("bad.json"), "{\"type\": \"nonsense\"}");
        GoogleDriveClient client = client(bad.toString());

        assertThatThrownBy(() -> client.listFiles(List.of("folder"))).isInstanceOf(IOException.class);
    }

    private static GoogleDriveClient client(String credentialsFile) {
        return new GoogleDriveClient(new Docs0Properties(null, null, null, null,
                new Docs0Properties.Drive(credentialsFile, "", 15, false)));
    }
}
