package com.android.inputmethod.keyboard;

import com.android.inputmethod.latin.utils.JniUtils;

public final class ProximityInfo implements AutoCloseable
{
  static { JniUtils.load_native_library(); }

  private long _native_proximity_info;

  @Override public void close()
  {
    if (_native_proximity_info != 0)
    {
      releaseProximityInfoNative(_native_proximity_info);
      _native_proximity_info = 0;
    }
  }

  private static native long setProximityInfoNative(int displayWidth, int displayHeight,
      int gridWidth, int gridHeight, int mostCommonKeyWidth, int mostCommonKeyHeight,
      int[] proximityChars, int keyCount, int[] keyXCoordinates, int[] keyYCoordinates,
      int[] keyWidths, int[] keyHeights, int[] keyCharCodes, float[] sweetSpotCenterXs,
      float[] sweetSpotCenterYs, float[] sweetSpotRadii);
  private static native void releaseProximityInfoNative(long nativeProximityInfo);
}
