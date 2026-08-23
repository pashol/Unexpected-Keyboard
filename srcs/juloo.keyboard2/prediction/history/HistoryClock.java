package juloo.keyboard2.prediction.history;

/** Supplies time so history snapshots are deterministic in tests. */
public interface HistoryClock
{
  long currentTimeMillis();
}
