package juloo.keyboard2.prediction;

import android.text.InputType;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import juloo.keyboard2.prediction.history.BoundedUserHistoryModel;
import juloo.keyboard2.prediction.history.UserHistoryModel;
import juloo.keyboard2.prediction.history.UserHistoryPersistence;
import juloo.keyboard2.prediction.history.UserHistoryStore;
import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionEngineFactoryTest
{
  @Test
  public void disabled_factory_does_not_create_experimental_resources()
  {
    CountingCreator creators = new CountingCreator();
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> creators.legacy(),
        (config, languageTag) -> creators.experimental());

    PredictionEngineController controller = factory.create(null, "en", false);

    assertEquals(1, creators.legacyCreates);
    assertEquals(0, creators.experimentalCreates);
    assertEquals("legacy", controller.predict(request()).get(0).getText());
  }

  @Test
  public void recoverable_experimental_construction_failure_returns_legacy_controller()
  {
    CountingCreator creators = new CountingCreator();
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> creators.legacy(),
        (config, languageTag) -> {
          creators.experimentalCreates++;
          throw new PredictionFailure("pack unavailable");
        });

    PredictionEngineController controller = factory.create(null, "en", true);

    assertEquals("legacy", controller.predict(request()).get(0).getText());
    assertEquals(1, creators.experimentalCreates);
  }

  @Test
  public void invalid_latin_pack_falls_back_to_legacy_controller()
  {
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> new CountingEngine("legacy"),
        (config, languageTag) -> { throw new LatinDecoder.InvalidDictionaryException("corrupt"); });

    PredictionEngineController controller = factory.create(null, "en", true);

    assertEquals("legacy", controller.predict(request()).get(0).getText());
  }

  @Test(expected = IllegalStateException.class)
  public void programming_runtime_from_experimental_creator_is_not_swallowed()
  {
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> new CountingEngine("legacy"),
        (config, languageTag) -> { throw new IllegalStateException("bug"); });

    factory.create(null, "en", true);
  }

  @Test
  public void unexpected_experimental_failure_closes_unowned_legacy_engine()
  {
    CountingEngine legacy = new CountingEngine("legacy");
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> legacy,
        (config, languageTag) -> { throw new IllegalStateException("bug"); });

    try {
      factory.create(null, "en", true);
      fail("expected construction failure");
    } catch (IllegalStateException expected) {
      assertEquals(1, legacy.closes);
    }
  }

  @Test(expected = AssertionError.class)
  public void error_from_experimental_creator_is_not_swallowed()
  {
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> new CountingEngine("legacy"),
        (config, languageTag) -> { throw new AssertionError("fatal"); });

    factory.create(null, "en", true);
  }

  @Test
  public void disabled_rebuild_skips_experimental_and_closes_replaced_engine_once()
  {
    CountingEngine oldLegacy = new CountingEngine("old legacy");
    CountingEngine oldExperimental = new CountingEngine("old experimental");
    PredictionEngineController controller =
        new PredictionEngineController(oldLegacy, oldExperimental, true);
    CountingCreator creators = new CountingCreator();
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> creators.legacy(),
        (config, languageTag) -> creators.experimental());

    factory.rebuild(controller, null, "fr", false);

    assertEquals(0, creators.experimentalCreates);
    assertEquals(1, oldLegacy.closes);
    assertEquals(1, oldExperimental.closes);
    assertEquals("legacy", controller.predict(request()).get(0).getText());
  }

  @Test
  public void rebuild_closes_old_experimental_owner_before_creating_replacement()
  {
    List<String> events = new ArrayList<String>();
    PredictionEngine oldExperimental = new OrderedEngine("old", events, "close");
    PredictionEngineController controller = new PredictionEngineController(
        new CountingEngine("legacy"), oldExperimental, true);
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> new CountingEngine("legacy"),
        (config, languageTag) -> new OrderedEngine("new", events, "create"));

    factory.rebuild(controller, null, "en", true);

    assertEquals(java.util.Arrays.asList("close", "create"), events);
  }

  @Test
  public void rebuild_closes_new_legacy_when_experimental_creation_throws()
  {
    CountingEngine oldLegacy = new CountingEngine("old legacy");
    CountingEngine newLegacy = new CountingEngine("new legacy");
    PredictionEngineController controller = new PredictionEngineController(
        oldLegacy, new CountingEngine("old experimental"), true);
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> newLegacy,
        (config, languageTag) -> { throw new IllegalStateException("bug"); });

    try {
      factory.rebuild(controller, null, "en", true);
      fail("expected construction failure");
    } catch (IllegalStateException expected) {
      assertEquals(1, newLegacy.closes);
    }
  }

  @Test
  public void rebuild_flushes_accepted_history_and_deletion_before_replacement_loads()
  {
    HistoryStore store = new HistoryStore();
    UserHistoryPersistence history = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");
    history.accept(Collections.<String>emptyList(), "kept");
    history.accept(Collections.<String>emptyList(), "deleted");
    history.delete("deleted");
    PredictionEngineController controller = new PredictionEngineController(
        new CountingEngine("legacy"), new HistoryOwnerEngine(history), true);
    PredictionEngineFactory factory = new PredictionEngineFactory(
        config -> new CountingEngine("legacy"),
        (config, languageTag) -> {
          assertEquals(1, store.snapshot.unigramCount("kept"));
          assertEquals(0, store.snapshot.unigramCount("deleted"));
          return new CountingEngine("replacement");
        });

    factory.rebuild(controller, null, "en", true);
  }

  @Test
  public void closes_history_when_decoder_construction_fails()
  {
    HistoryStore store = new HistoryStore();
    UserHistoryPersistence history = new UserHistoryPersistence(
        new BoundedUserHistoryModel(() -> 0L), store, "en");

    try {
      PredictionEngineFactory.createWithHistory(history, value -> {
        throw new PredictionFailure("decoder unavailable");
      });
      fail("expected construction failure");
    } catch (PredictionFailure expected) {
      assertTrue(history.isClosed());
    }
  }

  private static PredictionRequest request()
  {
    return new PredictionRequest("word", 4, Collections.emptyList(), false,
        "en", 3, 1L, EditorPredictionPolicy.from(
          InputType.TYPE_CLASS_TEXT, 0, null));
  }

  private static final class CountingCreator
  {
    int legacyCreates;
    int experimentalCreates;

    PredictionEngine legacy()
    {
      legacyCreates++;
      return new CountingEngine("legacy");
    }

    PredictionEngine experimental()
    {
      experimentalCreates++;
      return new CountingEngine("experimental");
    }
  }

  private static class CountingEngine implements PredictionEngine
  {
    final String text;
    int closes;

    CountingEngine(String text) { this.text = text; }

    public List<PredictionCandidate> predict(PredictionRequest request)
    {
      return Collections.singletonList(new PredictionCandidate(
          text, "en", CandidateType.COMPLETION, text, 0, 0, 0, 0, 0));
    }

    public void recordFeedback(PredictionFeedback feedback) {}
    public void resetSession() {}
    public void close() { closes++; }
  }

  private static final class OrderedEngine extends CountingEngine
  {
    final List<String> events;
    final String event;
    OrderedEngine(String text, List<String> events, String event)
    {
      super(text);
      this.events = events;
      this.event = event;
      if (event.equals("create")) events.add(event);
    }
    public void close() { events.add(event); }
  }

  private static final class HistoryOwnerEngine extends CountingEngine
  {
    final UserHistoryPersistence history;
    HistoryOwnerEngine(UserHistoryPersistence history) { super("history"); this.history = history; }
    public void close() { history.close(); }
  }

  private static final class HistoryStore implements UserHistoryStore
  {
    volatile UserHistoryModel snapshot = UserHistoryModel.empty(() -> 0L);
    public UserHistoryModel load(String locale) { return snapshot; }
    public void save(String locale, UserHistoryModel value) { snapshot = value; }
  }
}
