/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.android.inputmethod.latin.utils;


/** JNI registration surface only; application logic lives in EnglishEngine. */
public final class BinaryDictionaryUtils {
    private static native boolean createEmptyDictFileNative(String filePath, long dictVersion,
            String locale, String[] attributeKeyStringArray, String[] attributeValueStringArray);
    private static native float calcNormalizedScoreNative(int[] before, int[] after, int score);
    private static native int setCurrentTimeForTestNative(int currentTime);
}
