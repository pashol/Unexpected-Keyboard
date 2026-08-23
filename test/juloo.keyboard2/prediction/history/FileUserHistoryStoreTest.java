package juloo.keyboard2.prediction.history;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class FileUserHistoryStoreTest
{
  @Test
  public void persists_a_versioned_locale_snapshot_atomically() throws Exception
  {
    File directory = Files.createTempDirectory("prediction-history").toFile();
    BoundedUserHistoryModel history = new BoundedUserHistoryModel(() -> 100L);
    history.recordAccepted("gsw-CH", Arrays.asList("das"), "n\u00f6d");
    FileUserHistoryStore store = new FileUserHistoryStore(directory);

    store.save("gsw-CH", history.snapshot("gsw-CH"));
    UserHistoryModel restored = store.load("gsw-CH");

    assertEquals(1, restored.bigramCount("das", "n\u00f6d"));
    assertTrue(new File(directory, "gsw-CH.bin").isFile());
    assertFalse(new File(directory, "gsw-CH.bin.tmp").exists());
  }

  @Test
  public void corrupt_history_recovers_as_empty_snapshot() throws Exception
  {
    File directory = Files.createTempDirectory("prediction-history").toFile();
    File file = new File(directory, "en.bin");
    try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[] { 1, 2, 3 }); }

    assertEquals(0, new FileUserHistoryStore(directory).load("en").unigramCount("hello"));
  }

  @Test
  public void oversized_file_is_rejected_before_entry_allocation() throws Exception
  {
    File directory = Files.createTempDirectory("prediction-history").toFile();
    File file = new File(directory, "en.bin");
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(new byte[BoundedUserHistoryModel.MAX_BYTES + 1]);
    }

    assertEquals(0, new FileUserHistoryStore(directory).load("en").unigramCount("word"));
    assertFalse(file.exists());
  }
}
