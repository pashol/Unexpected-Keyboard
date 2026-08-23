package juloo.keyboard2.prediction;

import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

public class LatinPredictionPackResolverTest
{
  @Test(expected = PredictionFailure.class)
  public void missing_active_pack_fails_recoverably()
  {
    new LatinPredictionPackResolver(() -> new File("missing.latin.dict")).resolve();
  }
}
