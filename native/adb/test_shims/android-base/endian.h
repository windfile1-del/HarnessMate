// Host-only: the pinned AOSP AES source includes this but uses no endian API.
#pragma once
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error The AOSP pairing fixture requires a little-endian host
#endif
