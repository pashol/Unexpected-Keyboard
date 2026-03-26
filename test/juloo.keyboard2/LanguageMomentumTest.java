package juloo.keyboard2;

import org.junit.Test;
import static org.junit.Assert.*;
import juloo.keyboard2.suggestions.LanguageMomentum;

public class LanguageMomentumTest
{
  @Test
  public void initial_default_gets_head_start()
  {
    LanguageMomentum m = new LanguageMomentum(1);
    assertTrue("default dict should have momentum", m.score(1) > 0f);
    assertEquals("non-default should be zero", 0f, m.score(0), 0.001f);
  }

  @Test
  public void record_win_increases_score()
  {
    LanguageMomentum m = new LanguageMomentum(0);
    m.record(1);
    m.record(1);
    m.record(1);
    assertTrue("winning dict should have positive score", m.score(1) > 0f);
    assertTrue("winner beats loser", m.score(1) > m.score(0));
  }

  @Test
  public void full_window_of_wins_gives_1_0()
  {
    LanguageMomentum m = new LanguageMomentum(0);
    for (int i = 0; i < 8; i++) m.record(2);
    assertEquals(1.0f, m.score(2), 0.001f);
    assertEquals(0f, m.score(0), 0.001f);
  }

  @Test
  public void old_wins_slide_out()
  {
    LanguageMomentum m = new LanguageMomentum(0);
    for (int i = 0; i < 8; i++) m.record(0);
    for (int i = 0; i < 8; i++) m.record(1); // pushes all dict-0 wins out
    assertEquals("dict 0 evicted", 0f, m.score(0), 0.001f);
    assertEquals("dict 1 dominates", 1.0f, m.score(1), 0.001f);
  }

  @Test
  public void reset_restores_default_advantage()
  {
    LanguageMomentum m = new LanguageMomentum(0);
    for (int i = 0; i < 8; i++) m.record(1);
    m.reset(0);
    assertEquals(LanguageMomentum.INITIAL_MOMENTUM, m.score(0), 0.001f);
    assertEquals(0f, m.score(1), 0.001f);
  }

  @Test
  public void no_win_record_keeps_default_advantage()
  {
    LanguageMomentum m = new LanguageMomentum(0);
    m.record(-1);
    m.record(-1);
    assertTrue("default still has advantage with no matches", m.score(0) > 0f);
  }
}
