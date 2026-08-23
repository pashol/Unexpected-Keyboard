/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.keyboard;

/** Native key geometry used when scoring typed suggestions. */
public final class ProximityInfo implements AutoCloseable
{
  private long handle;

  public ProximityInfo(int displayWidth, int displayHeight, int gridWidth, int gridHeight,
      int commonKeyWidth, int commonKeyHeight, int[] proximityChars, int keyCount,
      int[] keyX, int[] keyY, int[] keyWidths, int[] keyHeights, int[] keyCodes,
      float[] sweetSpotX, float[] sweetSpotY, float[] sweetSpotRadii)
  {
    handle = setProximityInfoNative(displayWidth, displayHeight, gridWidth, gridHeight,
        commonKeyWidth, commonKeyHeight, proximityChars, keyCount, keyX, keyY, keyWidths,
        keyHeights, keyCodes, sweetSpotX, sweetSpotY, sweetSpotRadii);
  }

  public long handle() { return handle; }

  public void close()
  {
    if (handle != 0) {
      releaseProximityInfoNative(handle);
      handle = 0;
    }
  }

  private static native long setProximityInfoNative(int displayWidth, int displayHeight,
      int gridWidth, int gridHeight, int commonKeyWidth, int commonKeyHeight,
      int[] proximityChars, int keyCount, int[] keyX, int[] keyY, int[] keyWidths,
      int[] keyHeights, int[] keyCodes, float[] sweetSpotX, float[] sweetSpotY,
      float[] sweetSpotRadii);
  private static native void releaseProximityInfoNative(long proximityInfo);
}
