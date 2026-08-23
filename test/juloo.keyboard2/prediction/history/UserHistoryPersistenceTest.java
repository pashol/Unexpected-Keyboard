package juloo.keyboard2.prediction.history;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class UserHistoryPersistenceTest
{
  @Test
  public void fiftieth_feedback_flushes_on_its_single_background_worker() throws Exception
  {
    RecordingStore store = new RecordingStore();
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    for (int i = 0; i < 50; i++) persistence.accept(Arrays.<String>asList(), "word");

    assertTrue(store.saved.await(2, TimeUnit.SECONDS));
    assertEquals(50, store.snapshot.unigramCount("word"));
    persistence.close();
  }

  @Test
  public void learning_is_suppressed_when_editor_policy_disallows_it()
  {
    RecordingStore store = new RecordingStore();
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");

    persistence.accept(Arrays.<String>asList(), "private", false);

    assertEquals(0, persistence.snapshot().unigramCount("private"));
    persistence.close();
  }

  @Test
  public void policy_suppresses_rejection_and_reversion_mutations()
  {
    RecordingStore store = new RecordingStore();
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    persistence.accept(Arrays.<String>asList(), "kept");

    persistence.reject(Arrays.<String>asList(), "kept", false);
    persistence.revert(Arrays.<String>asList(), "kept", false);

    assertEquals(1, persistence.snapshot().unigramCount("kept"));
    persistence.close();
  }

  @Test
  public void explicit_deletion_is_flushed_durably()
  {
    RecordingStore store = new RecordingStore();
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    persistence.accept(Arrays.<String>asList(), "remove");
    persistence.delete("remove");

    persistence.close();

    assertEquals(0, store.snapshot.unigramCount("remove"));
  }

  @Test
  public void deletion_before_async_load_finishes_does_not_resurrect_history() throws Exception
  {
    BlockingStore store = new BlockingStore("deleted");
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    assertTrue(store.loading.await(2, TimeUnit.SECONDS));

    persistence.delete("deleted");
    store.release.countDown();
    persistence.close();

    assertEquals(0, persistence.snapshot().unigramCount("deleted"));
    assertEquals(0, store.snapshot.unigramCount("deleted"));
  }

  @Test
  public void feedback_recorded_before_async_load_merges_with_persisted_count() throws Exception
  {
    BlockingStore store = new BlockingStore("word", 2);
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    assertTrue(store.loading.await(2, TimeUnit.SECONDS));
    persistence.accept(Arrays.<String>asList(), "word");

    store.release.countDown();
    persistence.close();

    assertEquals(3, persistence.snapshot().unigramCount("word"));
    assertEquals(3, store.snapshot.unigramCount("word"));
  }

  @Test
  public void rejection_before_async_load_decrements_the_persisted_count() throws Exception
  {
    BlockingStore store = new BlockingStore("word", 2);
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    assertTrue(store.loading.await(2, TimeUnit.SECONDS));
    persistence.reject(Arrays.<String>asList(), "word");

    store.release.countDown();
    persistence.close();

    assertEquals(1, persistence.snapshot().unigramCount("word"));
    assertEquals(1, store.snapshot.unigramCount("word"));
  }

  @Test
  public void reversion_before_async_load_removes_a_single_persisted_count() throws Exception
  {
    BlockingStore store = new BlockingStore("word", 1);
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    assertTrue(store.loading.await(2, TimeUnit.SECONDS));
    persistence.revert(Arrays.<String>asList(), "word");

    store.release.countDown();
    persistence.close();

    assertEquals(0, persistence.snapshot().unigramCount("word"));
    assertEquals(0, store.snapshot.unigramCount("word"));
  }

  @Test
  public void tombstones_clear_after_their_deletion_snapshot_is_persisted()
  {
    RecordingStore store = new RecordingStore();
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    persistence.delete("deleted");

    persistence.close();

    assertEquals(0, persistence.tombstoneCount());
    assertEquals(0, store.snapshot.unigramCount("deleted"));
  }

  @Test
  public void tombstones_are_bounded_while_async_load_is_blocked() throws Exception
  {
    BlockingStore store = new BlockingStore("loaded");
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    assertTrue(store.loading.await(2, TimeUnit.SECONDS));
    for (int i = 0; i < UserHistoryPersistence.MAX_TOMBSTONES; i++)
      persistence.delete("deleted" + i);

    assertEquals(UserHistoryPersistence.MAX_TOMBSTONES, persistence.tombstoneCount());
    store.release.countDown();
    persistence.close();
  }

  @Test
  public void deletion_after_tombstone_capacity_waits_for_load_and_still_removes_loaded_word()
      throws Exception
  {
    BlockingStore store = new BlockingStore("target");
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    assertTrue(store.loading.await(2, TimeUnit.SECONDS));
    for (int i = 0; i < UserHistoryPersistence.MAX_TOMBSTONES; i++)
      persistence.delete("deleted" + i);
    Thread deletion = new Thread(() -> persistence.delete("target"));
    deletion.start();

    store.release.countDown();
    deletion.join(2000);
    persistence.close();

    assertFalse(deletion.isAlive());
    assertEquals(0, store.snapshot.unigramCount("target"));
  }

  @Test
  public void close_flushes_pending_feedback()
  {
    RecordingStore store = new RecordingStore();
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    persistence.accept(Arrays.<String>asList(), "saved");

    persistence.close();

    assertEquals(1, store.snapshot.unigramCount("saved"));
  }

  @Test
  public void snapshot_does_not_wait_for_a_blocking_background_save() throws Exception
  {
    BlockingSaveStore store = new BlockingSaveStore();
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    for (int i = 0; i < 50; i++) persistence.accept(Arrays.<String>asList(), "word");
    assertTrue(store.saving.await(2, TimeUnit.SECONDS));
    CountDownLatch snapshotted = new CountDownLatch(1);
    Thread reader = new Thread(() -> { persistence.snapshot(); snapshotted.countDown(); });
    reader.start();

    boolean returnedBeforeSave = snapshotted.await(200, TimeUnit.MILLISECONDS);
    store.release.countDown();
    reader.join(2000);
    persistence.close();

    assertTrue(returnedBeforeSave);
  }

  @Test
  public void save_failure_keeps_pending_feedback_and_retries_after_later_feedback() throws Exception
  {
    FailOnceStore store = new FailOnceStore();
    UserHistoryPersistence persistence = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    for (int i = 0; i < 50; i++) persistence.accept(Arrays.<String>asList(), "word");
    assertTrue(store.failed.await(2, TimeUnit.SECONDS));

    persistence.accept(Arrays.<String>asList(), "word");

    assertTrue(store.saved.await(2, TimeUnit.SECONDS));
    assertEquals(51, store.snapshot.unigramCount("word"));
    persistence.close();
  }

  private static class RecordingStore implements UserHistoryStore
  {
    final CountDownLatch saved = new CountDownLatch(1);
    volatile UserHistoryModel snapshot = UserHistoryModel.empty(() -> 0L);
    public UserHistoryModel load(String locale) { return snapshot; }
    public void save(String locale, UserHistoryModel value) { snapshot = value; saved.countDown(); }
  }

  private static final class BlockingStore extends RecordingStore
  {
    final CountDownLatch loading = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    BlockingStore(String word) { this(word, 1); }
    BlockingStore(String word, int count)
    {
      BoundedUserHistoryModel history = new BoundedUserHistoryModel(() -> 0L);
      for (int i = 0; i < count; i++) history.recordAccepted("en", Arrays.<String>asList(), word);
      snapshot = history.snapshot("en");
    }

    public UserHistoryModel load(String locale)
    {
      loading.countDown();
      try { release.await(); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      return snapshot;
    }
  }

  private static final class BlockingSaveStore extends RecordingStore
  {
    final CountDownLatch saving = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    public void save(String locale, UserHistoryModel value)
    {
      saving.countDown();
      try { release.await(); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      super.save(locale, value);
    }
  }

  private static final class FailOnceStore extends RecordingStore
  {
    final CountDownLatch failed = new CountDownLatch(1);
    boolean fail = true;
    public synchronized void save(String locale, UserHistoryModel value)
    {
      if (fail) {
        fail = false;
        failed.countDown();
        throw new IllegalStateException("disk full");
      }
      super.save(locale, value);
    }
  }
}
