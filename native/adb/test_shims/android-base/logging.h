// Host-only adapter for unmodified AOSP crypto reference, never linked into JNI.
#pragma once
#include <stdexcept>
struct AdbTestLog {template<class T>AdbTestLog& operator<<(const T&){return *this;}};
#define CHECK(x) do {if(!(x))throw std::runtime_error("AOSP_REFERENCE_CHECK_FAILED: " #x);}while(0)
#define CHECK_GT(x,y) CHECK((x)>(y))
#define CHECK_NE(x,y) CHECK((x)!=(y))
#define CHECK_EQ(x,y) CHECK((x)==(y))
#define LOG(x) AdbTestLog()
