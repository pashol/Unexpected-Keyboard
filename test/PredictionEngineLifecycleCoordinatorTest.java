package juloo.keyboard2;

import org.junit.Test;
import static org.junit.Assert.*;

public class PredictionEngineLifecycleCoordinatorTest
{
  @Test
  public void experimental_preference_change_rebuilds_once()
  {
    CountingTarget target = new CountingTarget();
    PredictionEngineLifecycleCoordinator coordinator =
        new PredictionEngineLifecycleCoordinator(target);

    coordinator.onPreferenceChanged("experimental_prediction_engine");

    assertEquals(1, target.rebuilds);
  }

  @Test
  public void unrelated_preference_change_does_not_rebuild()
  {
    CountingTarget target = new CountingTarget();
    PredictionEngineLifecycleCoordinator coordinator =
        new PredictionEngineLifecycleCoordinator(target);

    coordinator.onPreferenceChanged("theme");

    assertEquals(0, target.rebuilds);
  }

  @Test
  public void subtype_change_rebuilds_once()
  {
    CountingTarget target = new CountingTarget();
    PredictionEngineLifecycleCoordinator coordinator =
        new PredictionEngineLifecycleCoordinator(target);

    coordinator.onSubtypeChanged();

    assertEquals(1, target.rebuilds);
  }

  @Test
  public void finish_and_destroy_reset_and_close_once()
  {
    CountingTarget target = new CountingTarget();
    PredictionEngineLifecycleCoordinator coordinator =
        new PredictionEngineLifecycleCoordinator(target);

    coordinator.onInputFinished();
    coordinator.close();
    coordinator.close();

    assertEquals(1, target.resets);
    assertEquals(1, target.closes);
  }

  private static final class CountingTarget
      implements PredictionEngineLifecycleCoordinator.Target
  {
    int rebuilds;
    int resets;
    int closes;

    public void rebuildPredictionEngine() { rebuilds++; }
    public void resetPredictionSession() { resets++; }
    public void closePredictionEngines() { closes++; }
  }
}
