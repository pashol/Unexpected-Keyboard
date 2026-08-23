package juloo.keyboard2.prediction;

final class NativeDecoderLifecycle
{
  private boolean _open = true;

  boolean is_open()
  {
    return _open;
  }

  void close()
  {
    _open = false;
  }
}
