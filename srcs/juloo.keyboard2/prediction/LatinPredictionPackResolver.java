package juloo.keyboard2.prediction;

import java.io.File;
import java.util.Objects;

/** Resolves the LatinIME companion pack for the active installed dictionary. */
public final class LatinPredictionPackResolver
{
  public interface Location { File activePack(); }
  private final Location location;

  public LatinPredictionPackResolver(Location location)
  {
    this.location = Objects.requireNonNull(location, "location");
  }

  public File resolve()
  {
    File pack = location.activePack();
    if (pack == null || !pack.isFile() || !pack.canRead())
      throw new PredictionFailure("LatinIME pack unavailable");
    return pack;
  }
}
