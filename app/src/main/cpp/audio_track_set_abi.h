#pragma once

namespace melody::pcm {

enum class AudioTrackSetAbi { Legacy, WithString };

inline bool supportsPcmSdk(int sdk) {
    return sdk == 36 || sdk == 37;
}

struct AudioTrackSetSignature {
    const char* symbol;
    const char* label;
    AudioTrackSetAbi abi;
};

// Android 16 has 18 explicit arguments, including 32-bit channelMask and sessionId.
// The supplied Android 17 library (Build ID 841ce70b6ccd15c60a4af1f1252dabb8) adds a
// trailing const std::string&. Pass that reference opaquely; never use the shorter wrapper.
// These are complete ELF symbols, not prefixes or inferred signatures from the SDK number.
inline constexpr AudioTrackSetSignature kAudioTrackSetSignatures[] = {
        {"_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_tjm20audio_output_flags_t"
         "RKNS_2wpINS0_19IAudioTrackCallbackEEEiRKNS_2spINS_7IMemoryEEEbiNS0_13transfer_typeE"
         "PK20audio_offload_info_tRKNS_7content22AttributionSourceStateEPK18audio_attributes_tbfi",
         "integer_aliases", AudioTrackSetAbi::Legacy},
        {"_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_t20audio_channel_mask_tm"
         "20audio_output_flags_tRKNS_2wpINS0_19IAudioTrackCallbackEEEiRKNS_2spINS_7IMemoryEEE"
         "b15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tRKNS_7content22AttributionSourceStateE"
         "PK18audio_attributes_tbfi",
         "named_audio_enums", AudioTrackSetAbi::Legacy},
        {"_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_t20audio_channel_mask_tm"
         "20audio_output_flags_tRKNS_2wpINS0_19IAudioTrackCallbackEEEiRKNS_2spINS_7IMemoryEEE"
         "b15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tRKNS_7content22AttributionSourceStateE"
         "PK18audio_attributes_tbfiRKNSt3__112basic_stringIcNSR_11char_traitsIcEENSR_9allocatorIcEEEE",
         "named_audio_enums_with_string", AudioTrackSetAbi::WithString},
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
        if (result.address != nullptr && (result.address != address
                || result.signature->abi != signature.abi)) {
            // Distinct overloads leave an untracked path. Aliases with different parameter
            // lists cannot share a wrapper even when their entry address happens to agree.
            return {nullptr, nullptr, true};
        }
        if (result.address == nullptr) result = {&signature, address, false};
    }
    return result;
}

}  // namespace melody::pcm
