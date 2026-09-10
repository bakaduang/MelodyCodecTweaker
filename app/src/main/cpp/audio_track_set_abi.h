#pragma once

namespace melody::pcm {

struct AudioTrackSetSignature {
    const char* symbol;
    const char* label;
};

// Both complete signatures have the same Android 16 arm64 calling convention: 18 explicit
// arguments, including 32-bit channelMask and sessionId. The named-enum export is verified in
// the user's libaudioclient.so, Build ID e5ed64db3ed9ddca14c494145a509ee6. No prefix matching.
inline constexpr AudioTrackSetSignature kAudioTrackSetSignatures[] = {
        {"_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_t"
         "RKNS_2wpINS0_19IAudioTrackCallbackEEEiRKNS_2spINS_7IMemoryEEEbiNS0_13transfer_typeE"
         "PK20audio_offload_info_tRKNS_7content22AttributionSourceStateEPK18audio_attributes_tbfi",
         "integer_aliases"},
        {"_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_t20audio_channel_mask_tm"
         "20audio_output_flags_tRKNS_2wpINS0_19IAudioTrackCallbackEEEiRKNS_2spINS_7IMemoryEEE"
         "b15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tRKNS_7content22AttributionSourceStateE"
         "PK18audio_attributes_tbfi",
         "named_audio_enums"},
};

struct AudioTrackSetSelection {
    const AudioTrackSetSignature* signature = nullptr;
    void* address = nullptr;
    bool ambiguous = false;
};

template <class Lookup>
AudioTrackSetSelection findAudioTrackSet(Lookup lookup) {
    AudioTrackSetSelection result;
    for (const auto& signature : kAudioTrackSetSignatures) {
        void* address = lookup(signature.symbol);
        if (address == nullptr) continue;
        if (result.address != nullptr && result.address != address) {
            // Choosing just one of two distinct overloads would leave an untracked audio path.
            return {nullptr, nullptr, true};
        }
        if (result.address == nullptr) result = {&signature, address, false};
    }
    return result;
}

}  // namespace melody::pcm
