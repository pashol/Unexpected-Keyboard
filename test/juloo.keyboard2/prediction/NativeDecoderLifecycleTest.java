package juloo.keyboard2.prediction;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeDecoderLifecycleTest
{
  @Test public void close_is_idempotent_and_prevents_further_queries()
  {
    NativeDecoderLifecycle lifecycle = new NativeDecoderLifecycle();

    assertTrue(lifecycle.is_open());
    lifecycle.close();
    lifecycle.close();

    assertFalse(lifecycle.is_open());
  }
}
