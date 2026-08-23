LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_C_INCLUDES += $(LOCAL_PATH)/cdict/libcdict
LOCAL_CFLAGS +=
# -Wall -Wextra -Werror
LOCAL_SRC_FILES := cdict/libcdict/libcdict.c cdict/java/jni/juloo_cdict_Cdict.c
LOCAL_MODULE := libcdict_java
LOCAL_SDK_VERSION := 21

# Android requires 16K pages
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
# Disable build id to ensure reproducibility, needed for F-Droid
LOCAL_LDFLAGS += -Wl,--build-id=none

include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE := jni_latinime
LOCAL_SDK_VERSION := 21
LOCAL_C_INCLUDES := $(LOCAL_PATH)/latinime/native/jni/src
LOCAL_CPPFLAGS := -std=c++14
LOCAL_SRC_FILES := \
  latinime/native/jni/com_android_inputmethod_keyboard_ProximityInfo.cpp \
  latinime/native/jni/com_android_inputmethod_latin_BinaryDictionary.cpp \
  latinime/native/jni/com_android_inputmethod_latin_DicTraverseSession.cpp \
  latinime/native/jni/jni_common.cpp
include $(LOCAL_PATH)/latinime/native-sources.mk
LOCAL_SRC_FILES += $(LATIN_IME_CORE_SRC_FILES)

# Android requires 16K pages and F-Droid builds must be reproducible.
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,--build-id=none

include $(BUILD_SHARED_LIBRARY)
