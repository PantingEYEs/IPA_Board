/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.android.inputmethod.latin;


/** JNI registration surface only; application logic lives in EnglishEngine. */
public final class DicTraverseSession {
    public static native long setDicTraverseSessionNative(String locale, long dictSize);
    public static native void initDicTraverseSessionNative(long nativeDicTraverseSession,
            long dictionary, int[] previousWord, int previousWordLength);
    public static native void releaseDicTraverseSessionNative(long nativeDicTraverseSession);
}
