#pragma once

namespace melody::pcm {

using CompleteDestructorFn = void (*)(void*);
using BaseDestructorFn = void (*)(void*, const void*);

// AudioTrack has a virtual RefBase ancestor. Its D2 base-object destructor takes an opaque
// VTT as the second argument even though that argument is absent from the D2Ev mangled name.
// Preserve the caller's VTT: a derived object's sub-VTT need not be AudioTrack's global VTT.
// https://itanium-cxx-abi.github.io/cxx-abi/abi.html#vtt-parameters
inline void forwardBaseDestructor(void* self, const void* vtt,
        BaseDestructorFn destroy, void (*forget)(void*)) {
    forget(self);
    if (destroy != nullptr) destroy(self, vtt);
}

inline bool baseDestructorIsDistinct(void* complete0, void* complete1, void* base) {
    return base != nullptr && base != complete0 && base != complete1;
}

}  // namespace melody::pcm
