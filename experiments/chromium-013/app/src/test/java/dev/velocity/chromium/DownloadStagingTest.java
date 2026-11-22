package dev.velocity.chromium;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DownloadStagingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final UUID id = UUID.fromString("ab260293-b20c-428f-894f-2af18b77b127");

    @Test public void onlyOwnedRegularTargetsCanBeRecovered() throws Exception {
        File root = temporary.newFolder().getCanonicalFile();
        File target = DownloadStaging.target(root, false, id, "../report.pdf");
        assertEquals("report.pdf", DownloadStaging.normalFilename(root, target.getPath()));
        assertNull(DownloadStaging.normalFilename(root, target.getName()));
        assertNull(DownloadStaging.normalFilename(root, new File(root, "sub/../" + target.getName()).getPath()));
        assertNull(DownloadStaging.normalFilename(root, new File(root.getParentFile(), target.getName()).getPath()));
        assertNull(DownloadStaging.normalFilename(root,
                DownloadStaging.target(root, true, id, "private.pdf").getPath()));
    }

    @Test public void rejectsLinkOrDirectoryAndIdentifiesPrivatePartials() throws Exception {
        File root = temporary.newFolder().getCanonicalFile();
        File outside = temporary.newFile();
        File target = DownloadStaging.target(root, false, id, "report.pdf");
        Files.createSymbolicLink(target.toPath(), outside.toPath());
        assertNull(DownloadStaging.normalFilename(root, target.getPath()));
        Files.delete(target.toPath());
        assertTrue(target.mkdir());
        assertNull(DownloadStaging.normalFilename(root, target.getPath()));
        File partial = new File(root,
                DownloadStaging.target(root, true, id, "private.pdf").getName() + ".crdownload");
        assertTrue(partial.createNewFile());
        assertTrue(DownloadStaging.isPrivateStagingFile(partial));
        File unconfirmed = new File(root, "Unconfirmed 12345.crdownload");
        assertTrue(unconfirmed.createNewFile());
        assertTrue(DownloadStaging.isPrivateStagingFile(unconfirmed));
        assertFalse(DownloadStaging.isPrivateStagingFile(outside));
    }
}
