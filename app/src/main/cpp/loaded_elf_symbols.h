#pragma once

#include <elf.h>
#include <link.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

// A read-only resolver for an already loaded arm64 system library. It does not dlopen a private
// system path through an app linker namespace, inspect private linker structs, or match prefixes.
namespace melody::elf {

class LoadedImage {
public:
    char path[1024]{};
    char buildId[129]{};
    uintptr_t base = 0;

    bool initialize(const dl_phdr_info& info) {
        base = info.dlpi_addr;
        if (base == 0 || info.dlpi_phnum == 0 || info.dlpi_phnum > 256) return false;
        const Elf64_Phdr* dynamic = nullptr;
        for (size_t i = 0; i < info.dlpi_phnum; ++i) {
            const auto& phdr = info.dlpi_phdr[i];
            if (phdr.p_type == PT_LOAD) {
                if (segmentCount_ == kMaxSegments || UINTPTR_MAX - base < phdr.p_vaddr) return false;
                const uintptr_t begin = base + phdr.p_vaddr;
                if (UINTPTR_MAX - begin < phdr.p_memsz) return false;
                segments_[segmentCount_++] = {begin, begin + phdr.p_memsz, phdr.p_flags};
            } else if (phdr.p_type == PT_DYNAMIC) {
                if (dynamic != nullptr) return false;
                dynamic = &phdr;
            }
        }
        if (!contains(base, sizeof(Elf64_Ehdr), PF_R)) return false;
        Elf64_Ehdr header{};
        memcpy(&header, reinterpret_cast<const void*>(base), sizeof(header));
        if (memcmp(header.e_ident, ELFMAG, SELFMAG) != 0
                || header.e_ident[EI_CLASS] != ELFCLASS64
                || header.e_ident[EI_DATA] != ELFDATA2LSB
                || header.e_machine != EM_AARCH64 || header.e_type != ET_DYN) return false;
        if (dynamic == nullptr || UINTPTR_MAX - base < dynamic->p_vaddr) return false;
        const uintptr_t dynamicAddress = base + dynamic->p_vaddr;
        if (dynamic->p_memsz == 0 || dynamic->p_memsz > 1024 * 1024
                || !contains(dynamicAddress, dynamic->p_memsz, PF_R)) return false;

        uintptr_t symbolValue = 0, stringValue = 0, hashValue = 0, gnuHashValue = 0;
        size_t symbolEntrySize = 0;
        bool terminated = false;
        for (size_t i = 0; i < dynamic->p_memsz / sizeof(Elf64_Dyn); ++i) {
            Elf64_Dyn entry{};
            memcpy(&entry, reinterpret_cast<const void*>(dynamicAddress + i * sizeof(entry)),
                   sizeof(entry));
            if (entry.d_tag == DT_NULL) { terminated = true; break; }
            switch (entry.d_tag) {
                case DT_SYMTAB: symbolValue = entry.d_un.d_ptr; break;
                case DT_STRTAB: stringValue = entry.d_un.d_ptr; break;
                case DT_STRSZ: stringSize_ = entry.d_un.d_val; break;
                case DT_SYMENT: symbolEntrySize = entry.d_un.d_val; break;
                case DT_HASH: hashValue = entry.d_un.d_ptr; break;
                case DT_GNU_HASH: gnuHashValue = entry.d_un.d_ptr; break;
                default: break;
            }
        }
        if (!terminated || symbolEntrySize != sizeof(Elf64_Sym) || stringSize_ == 0
                || stringSize_ > kMaxStringBytes) return false;
        symbols_ = dynamicPointer(symbolValue);
        strings_ = dynamicPointer(stringValue);
        if (symbols_ == 0 || strings_ == 0 || !contains(strings_, stringSize_, PF_R)) return false;
        const uintptr_t hash = dynamicPointer(hashValue);
        const uintptr_t gnuHash = dynamicPointer(gnuHashValue);
        if (hash != 0) {
            uint32_t fields[2]{};
            if (!copy(hash, fields, sizeof(fields))) return false;
            const uint64_t words = 2ULL + fields[0] + fields[1];
            if (fields[0] > kMaxSymbols || fields[1] == 0 || fields[1] > kMaxSymbols
                    || !contains(hash, words * sizeof(uint32_t), PF_R)) return false;
            symbolCount_ = fields[1];
        } else if (gnuHash != 0) {
            if (!countGnuSymbols(gnuHash, &symbolCount_)) return false;
        } else {
            // No guessed dynsym length from an adjacent section or a memory mapping boundary.
            return false;
        }
        if (!contains(symbols_, symbolCount_ * sizeof(Elf64_Sym), PF_R)) return false;
        snprintf(path, sizeof(path), "%s", info.dlpi_name == nullptr ? "" : info.dlpi_name);
        readBuildId(info);
        return true;
    }

    void* findFunction(const char* exactName) const {
        const size_t length = strlen(exactName);
        uintptr_t found = 0;
        for (size_t i = 0; i < symbolCount_; ++i) {
            Elf64_Sym symbol{};
            memcpy(&symbol, reinterpret_cast<const void*>(symbols_ + i * sizeof(symbol)),
                   sizeof(symbol));
            const unsigned bind = ELF64_ST_BIND(symbol.st_info);
            if (symbol.st_shndx == SHN_UNDEF || symbol.st_value == 0
                    || ELF64_ST_TYPE(symbol.st_info) != STT_FUNC
                    || (bind != STB_GLOBAL && bind != STB_WEAK)
                    || symbol.st_name >= stringSize_ || length >= stringSize_ - symbol.st_name) {
                continue;
            }
            const auto* name = reinterpret_cast<const char*>(strings_ + symbol.st_name);
            if (memcmp(name, exactName, length) != 0 || name[length] != '\0') continue;
            if (UINTPTR_MAX - base < symbol.st_value) return nullptr;
            const uintptr_t address = base + symbol.st_value;
            if ((address & 3U) != 0 || !contains(address, sizeof(uint32_t), PF_X)) return nullptr;
            if (found != 0 && found != address) return nullptr;
            found = address;
        }
        return reinterpret_cast<void*>(found);
    }

private:
    static constexpr size_t kMaxSegments = 24;
    static constexpr size_t kMaxSymbols = 1024 * 1024;
    static constexpr size_t kMaxStringBytes = 16 * 1024 * 1024;
    struct Segment { uintptr_t begin; uintptr_t end; uint32_t flags; };
    Segment segments_[kMaxSegments]{};
    size_t segmentCount_ = 0;
    uintptr_t symbols_ = 0;
    uintptr_t strings_ = 0;
    size_t stringSize_ = 0;
    size_t symbolCount_ = 0;

    bool contains(uintptr_t address, size_t bytes, uint32_t flags) const {
        if (address == 0 || UINTPTR_MAX - address < bytes) return false;
        for (size_t i = 0; i < segmentCount_; ++i) {
            const auto& segment = segments_[i];
            if ((segment.flags & flags) == flags && address >= segment.begin
                    && address < segment.end && address + bytes <= segment.end) return true;
        }
        return false;
    }

    bool copy(uintptr_t address, void* destination, size_t bytes) const {
        if (!contains(address, bytes, PF_R)) return false;
        memcpy(destination, reinterpret_cast<const void*>(address), bytes);
        return true;
    }

    uintptr_t dynamicPointer(uintptr_t value) const {
        if (value == 0) return 0;
        // Android retains relative dynamic pointers; accepting the already-relocated form only
        // when it points inside this same image also supports loaders that relocate DT_* entries.
        if (UINTPTR_MAX - base >= value && contains(base + value, 1, PF_R)) return base + value;
        return contains(value, 1, PF_R) ? value : 0;
    }

    bool countGnuSymbols(uintptr_t hash, size_t* result) const {
        uint32_t header[4]{};
        if (!copy(hash, header, sizeof(header))) return false;
        const size_t bucketCount = header[0], firstSymbol = header[1], bloomCount = header[2];
        if (bucketCount == 0 || bucketCount > kMaxSymbols || firstSymbol > kMaxSymbols
                || bloomCount == 0 || bloomCount > kMaxSymbols) return false;
        const size_t prefixBytes = sizeof(header) + bloomCount * sizeof(Elf64_Xword);
        const size_t bucketBytes = bucketCount * sizeof(uint32_t);
        if (!contains(hash, prefixBytes + bucketBytes, PF_R)) return false;
        const uintptr_t buckets = hash + prefixBytes;
        const uintptr_t chains = buckets + bucketBytes;
        uint32_t highestBucket = 0;
        for (size_t i = 0; i < bucketCount; ++i) {
            uint32_t bucket = 0;
            memcpy(&bucket, reinterpret_cast<const void*>(buckets + i * sizeof(bucket)), sizeof(bucket));
            if (bucket != 0 && (bucket < firstSymbol || bucket >= kMaxSymbols)) return false;
            if (bucket > highestBucket) highestBucket = bucket;
        }
        if (highestBucket == 0) { *result = firstSymbol; return firstSymbol != 0; }
        // The last nonempty GNU bucket starts the final contiguous symbol chain.
        for (size_t symbol = highestBucket; symbol < kMaxSymbols; ++symbol) {
            uint32_t chain = 0;
            const size_t offset = (symbol - firstSymbol) * sizeof(chain);
            if (UINTPTR_MAX - chains < offset || !copy(chains + offset, &chain, sizeof(chain))) return false;
            if ((chain & 1U) != 0) { *result = symbol + 1; return true; }
        }
        return false;
    }

    void readBuildId(const dl_phdr_info& info) {
        for (size_t i = 0; i < info.dlpi_phnum; ++i) {
            const auto& phdr = info.dlpi_phdr[i];
            if (phdr.p_type != PT_NOTE || phdr.p_memsz > 1024 * 1024
                    || UINTPTR_MAX - base < phdr.p_vaddr) continue;
            const uintptr_t notes = base + phdr.p_vaddr;
            if (!contains(notes, phdr.p_memsz, PF_R)) continue;
            size_t offset = 0;
            while (offset <= phdr.p_memsz && phdr.p_memsz - offset >= sizeof(Elf64_Nhdr)) {
                Elf64_Nhdr note{};
                memcpy(&note, reinterpret_cast<const void*>(notes + offset), sizeof(note));
                offset += sizeof(note);
                const uint64_t nameBytes = (static_cast<uint64_t>(note.n_namesz) + 3) & ~3ULL;
                const uint64_t descBytes = (static_cast<uint64_t>(note.n_descsz) + 3) & ~3ULL;
                if (nameBytes + descBytes > phdr.p_memsz - offset) break;
                if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4
                        && note.n_descsz > 0 && note.n_descsz <= 64
                        && memcmp(reinterpret_cast<const void*>(notes + offset), "GNU", 4) == 0) {
                    const auto* bytes = reinterpret_cast<const unsigned char*>(notes + offset + nameBytes);
                    static constexpr char hex[] = "0123456789abcdef";
                    for (size_t j = 0; j < note.n_descsz; ++j) {
                        buildId[2 * j] = hex[bytes[j] >> 4];
                        buildId[2 * j + 1] = hex[bytes[j] & 15];
                    }
                    buildId[2 * note.n_descsz] = '\0';
                    return;
                }
                offset += static_cast<size_t>(nameBytes + descBytes);
            }
        }
    }
};

inline bool isAudioClientPath(const char* path) {
    if (path == nullptr) return false;
    const char* name = strrchr(path, '/');
    if (name == nullptr || strcmp(name + 1, "libaudioclient.so") != 0) return false;
    // Do not interpret an app's bundled library with a coincidentally identical basename as AOSP.
    return strncmp(path, "/system/", 8) == 0 || strncmp(path, "/system_ext/", 12) == 0
            || strncmp(path, "/apex/", 6) == 0;
}

inline bool findAudioClient(LoadedImage* image) {
    struct Search { LoadedImage* image; size_t matches; bool valid; } search{image, 0, false};
    dl_iterate_phdr([](dl_phdr_info* info, size_t, void* opaque) -> int {
        auto* state = static_cast<Search*>(opaque);
        if (isAudioClientPath(info->dlpi_name)) {
            ++state->matches;
            if (state->matches == 1) state->valid = state->image->initialize(*info);
        }
        return 0;
    }, &search);
    return search.matches == 1 && search.valid;
}

}  // namespace melody::elf
