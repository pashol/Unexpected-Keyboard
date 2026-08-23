package juloo.keyboard2.prediction.history;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class BoundedUserHistoryModelTest
{
  private static final long DAY = 24L * 60 * 60 * 1000;

  @Test
  public void accepted_words_record_unigram_bigram_and_trigram_counts()
  {
    FakeClock clock = new FakeClock();
    BoundedUserHistoryModel history = new BoundedUserHistoryModel(clock);

    history.recordAccepted("en", Arrays.asList("one", "two"), "three");
    UserHistoryModel snapshot = history.snapshot("en");

    assertEquals(1, snapshot.unigramCount("three"));
    assertEquals(1, snapshot.bigramCount("two", "three"));
    assertEquals(1, snapshot.trigramCount("one", "two", "three"));
  }

  @Test
  public void histories_are_isolated_by_locale()
  {
    BoundedUserHistoryModel history = new BoundedUserHistoryModel(new FakeClock());

    history.recordAccepted("gsw-CH", Arrays.asList("das"), "n\u00f6d");

    assertEquals(1, history.snapshot("gsw-CH").bigramCount("das", "n\u00f6d"));
    assertEquals(0, history.snapshot("de-CH").bigramCount("das", "n\u00f6d"));
  }

  @Test
  public void counts_decay_after_thirty_days()
  {
    FakeClock clock = new FakeClock();
    BoundedUserHistoryModel history = new BoundedUserHistoryModel(clock);
    history.recordAccepted("en", Arrays.<String>asList(), "hello");
    clock.now += 30 * DAY;

    assertEquals(0.5, history.snapshot("en").unigramScore("hello"), 0.0001);
  }

  @Test
  public void rejected_or_reverted_candidate_decrements_learned_counts()
  {
    BoundedUserHistoryModel history = new BoundedUserHistoryModel(new FakeClock());
    history.recordAccepted("en", Arrays.<String>asList(), "hello");
    history.recordRejected("en", Arrays.<String>asList(), "hello");
    history.recordAccepted("en", Arrays.<String>asList(), "hello");
    history.recordReverted("en", Arrays.<String>asList(), "hello");

    assertEquals(0, history.snapshot("en").unigramCount("hello"));
  }

  @Test
  public void explicitly_deleted_word_is_removed_from_all_ngrams()
  {
    BoundedUserHistoryModel history = new BoundedUserHistoryModel(new FakeClock());
    history.recordAccepted("en", Arrays.asList("one", "two"), "three");

    history.delete("en", "two");

    assertEquals(0, history.snapshot("en").bigramCount("two", "three"));
    assertEquals(0, history.snapshot("en").trigramCount("one", "two", "three"));
  }

  @Test
  public void capacity_evicts_oldest_entry_deterministically()
  {
    FakeClock clock = new FakeClock();
    BoundedUserHistoryModel history = new BoundedUserHistoryModel(clock, 2, 1024 * 1024);
    history.recordAccepted("en", Arrays.<String>asList(), "zulu");
    history.recordAccepted("en", Arrays.<String>asList(), "alpha");
    history.recordAccepted("en", Arrays.<String>asList(), "bravo");

    UserHistoryModel snapshot = history.snapshot("en");
    assertEquals(0, snapshot.unigramCount("alpha"));
    assertEquals(1, snapshot.unigramCount("bravo"));
    assertEquals(1, snapshot.unigramCount("zulu"));
  }

  @Test
  public void default_cap_keeps_at_most_twenty_thousand_entries()
  {
    BoundedUserHistoryModel history = new BoundedUserHistoryModel(new FakeClock());
    for (int i = 0; i <= BoundedUserHistoryModel.MAX_ENTRIES; i++)
      history.recordAccepted("en", Arrays.<String>asList(), "word" + i);

    assertEquals(BoundedUserHistoryModel.MAX_ENTRIES, history.snapshot("en").size());
  }

  @Test
  public void byte_cap_evicts_until_snapshot_fits_its_budget()
  {
    BoundedUserHistoryModel history = new BoundedUserHistoryModel(new FakeClock(), 100, 56);
    history.recordAccepted("en", Arrays.<String>asList(), "aaaaaaaaaaaaaaaa");
    history.recordAccepted("en", Arrays.<String>asList(), "bbbbbbbbbbbbbbbb");

    assertEquals(1, history.snapshot("en").size());
  }

  @Test
  public void entry_that_cannot_be_encoded_by_write_utf_is_not_retained()
  {
    StringBuilder word = new StringBuilder();
    for (int i = 0; i < 30000; i++) word.append('\u0800');
    BoundedUserHistoryModel history = new BoundedUserHistoryModel(new FakeClock());

    history.recordAccepted("en", Arrays.<String>asList(), word.toString());

    assertEquals(0, history.snapshot("en").size());
  }

  private static final class FakeClock implements HistoryClock
  {
    long now;
    public long currentTimeMillis() { return now; }
  }
}
