// Host stand-in for the NDK's <android/log.h> (compile check only).
#pragma once
#include <stdio.h>
enum { ANDROID_LOG_INFO = 4 };
static inline int __android_log_write(int, const char *tag, const char *text) {
    return fprintf(stderr, "%s: %s\n", tag, text);
}
