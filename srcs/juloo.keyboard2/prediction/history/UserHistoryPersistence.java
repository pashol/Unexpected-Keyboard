package juloo.keyboard2.prediction.history;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns asynchronous, batched persistence for one locale's mutable history. */
public final class UserHistoryPersistence implements AutoCloseable
{
  static final int MAX_TOMBSTONES = BoundedUserHistoryModel.MAX_ENTRIES * 3;
  private final BoundedUserHistoryModel model;
  private final UserHistoryStore store;
  private final String locale;
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  private int pendingFeedback;
  private boolean delayedFlushScheduled;
  private ScheduledFuture<?> delayedFlush;
  private boolean immediateFlushQueued;
  private boolean closed;
  private boolean loadMerged;
  private long deletionVersion;
  private final Set<String> deletedWords = new HashSet<>();
  private final List<Decrement> preLoadDecrements = new ArrayList<>();

  public UserHistoryPersistence(BoundedUserHistoryModel model, UserHistoryStore store, String locale)
  {
    this.model = Objects.requireNonNull(model, "model");
    this.store = Objects.requireNonNull(store, "store");
    this.locale = Objects.requireNonNull(locale, "locale");
    executor.execute(() -> {
      UserHistoryModel loaded = store.load(locale);
      synchronized (UserHistoryPersistence.this) {
        model.mergeLoaded(locale, loaded, deletedWords);
        for (Decrement decrement : preLoadDecrements)
          model.recordRejected(locale, decrement.preceding, decrement.word);
        preLoadDecrements.clear();
        loadMerged = true;
        UserHistoryPersistence.this.notifyAll();
      }
    });
  }

  public void accept(List<String> preceding, String word) { accept(preceding, word, true); }
  public synchronized void accept(List<String> preceding, String word, boolean allowsLearning)
  {
    if (closed || !allowsLearning) return;
    model.recordAccepted(locale, preceding, word);
    changed();
  }

  public synchronized void reject(List<String> preceding, String word)
  { reject(preceding, word, true); }
  public synchronized void reject(List<String> preceding, String word, boolean allowsLearning)
  {
    if (closed || !allowsLearning) return;
    model.recordRejected(locale, preceding, word);
    if (!loadMerged) preLoadDecrements.add(new Decrement(preceding, word));
    changed();
  }

  public synchronized void revert(List<String> preceding, String word)
  { revert(preceding, word, true); }
  public synchronized void revert(List<String> preceding, String word, boolean allowsLearning)
  {
    if (closed || !allowsLearning) return;
    model.recordReverted(locale, preceding, word);
    if (!loadMerged) preLoadDecrements.add(new Decrement(preceding, word));
    changed();
  }

  public synchronized UserHistoryModel snapshot() { return model.snapshot(locale); }
  synchronized int tombstoneCount() { return deletedWords.size(); }
  public synchronized boolean isClosed() { return closed; }

  public synchronized void delete(String word)
  {
    if (closed) return;
    while (!loadMerged && deletedWords.size() >= MAX_TOMBSTONES) {
      try { wait(); }
      catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("history load interrupted", interrupted);
      }
    }
    if (!loadMerged) deletedWords.add(word);
    deletionVersion++;
    model.delete(locale, word);
    changed();
  }

  private void changed()
  {
    pendingFeedback++;
    if (!delayedFlushScheduled) {
      delayedFlushScheduled = true;
      delayedFlush = executor.schedule(this::flush, 30, TimeUnit.SECONDS);
    }
    if (pendingFeedback >= 50 && !immediateFlushQueued) {
      immediateFlushQueued = true;
      executor.execute(this::flush);
    }
  }

  private void flush()
  {
    UserHistoryModel captured;
    int capturedFeedback;
    long capturedDeletionVersion;
    boolean capturedLoadMerged;
    synchronized (this) {
      if (pendingFeedback == 0) return;
      captured = model.snapshot(locale);
      capturedFeedback = pendingFeedback;
      capturedDeletionVersion = deletionVersion;
      capturedLoadMerged = loadMerged;
      immediateFlushQueued = false;
    }
    try {
      store.save(locale, captured);
    } catch (RuntimeException failure) {
      synchronized (this) {
        if (delayedFlush != null) delayedFlush.cancel(false);
        delayedFlushScheduled = false;
        delayedFlush = null;
      }
      return;
    }
    synchronized (this) {
      pendingFeedback -= capturedFeedback;
      if (capturedLoadMerged && deletionVersion == capturedDeletionVersion) deletedWords.clear();
      if (pendingFeedback == 0) {
        if (delayedFlush != null) delayedFlush.cancel(false);
        delayedFlushScheduled = false;
        delayedFlush = null;
      }
    }
  }

  public void close()
  {
    synchronized (this) {
      if (closed) return;
      closed = true;
      if (delayedFlush != null) delayedFlush.cancel(false);
    }
    executor.execute(this::flush);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("history flush timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("history flush interrupted", interrupted);
    }
  }

  private static final class Decrement
  {
    final List<String> preceding;
    final String word;
    Decrement(List<String> preceding, String word)
    {
      this.preceding = new ArrayList<String>(preceding);
      this.word = word;
    }
  }
}
