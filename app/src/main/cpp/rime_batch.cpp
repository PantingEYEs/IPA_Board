#include <rime_api.h>
#include <rime/menu.h>
#include <rime/context.h>
#include <rime/service.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <limits>

// A C-compatible, owned snapshot; no C++ objects or pointers cross the JNA boundary.
// Offsets refer to bytes of Rime's encoded input, not Kotlin UTF-16 or displayed Zhuyin.
namespace {
constexpr uint32_t kMagic = 0x52494d45;
constexpr uint32_t kVersion = 3;
constexpr size_t kMaxCandidates = 40;
constexpr size_t kMaxTextBytes = 16384;
constexpr size_t kMaxPacketBytes = 1024 * 1024;
enum Status : uint32_t { ok, no_session, input_mismatch, unsupported_api, invalid_argument, native_failure, oversized };
struct Header { uint32_t magic, version, status, count, input_bytes, total_bytes, prefix_end, committed_input_bytes; };
struct Entry { uint32_t rank, start, end, text_offset, text_bytes; };
static_assert(sizeof(Header) == 32 && sizeof(Entry) == 20);
struct Row { uint32_t rank, start, end; std::string text; };

void* packet(Status status, uint32_t input_bytes, const std::vector<Row>& rows, uint32_t* size, uint32_t prefix_end = 0, uint32_t committed_input_bytes = 0) {
    size_t length = sizeof(Header) + rows.size() * sizeof(Entry);
    for (const auto& row : rows) length += row.text.size();
    if (length > kMaxPacketBytes) return nullptr;
    auto* memory = static_cast<unsigned char*>(std::calloc(1, length));
    if (!memory) return nullptr;
    auto* header = reinterpret_cast<Header*>(memory);
    *header = {kMagic, kVersion, status, static_cast<uint32_t>(rows.size()), input_bytes, static_cast<uint32_t>(length), prefix_end, committed_input_bytes};
    auto* entries = reinterpret_cast<Entry*>(memory + sizeof(Header));
    size_t offset = sizeof(Header) + rows.size() * sizeof(Entry);
    for (size_t i = 0; i < rows.size(); ++i) {
        const auto& row = rows[i];
        entries[i] = {row.rank, row.start, row.end, static_cast<uint32_t>(offset), static_cast<uint32_t>(row.text.size())};
        std::memcpy(memory + offset, row.text.data(), row.text.size());
        offset += row.text.size();
    }
    *size = static_cast<uint32_t>(length);
    return memory;
}

struct IteratorGuard {
    RimeApi* api;
    RimeCandidateListIterator iterator{};
    bool active = false;
    ~IteratorGuard() { if (active) api->candidate_list_end(&iterator); }
};
}

extern "C" __attribute__((visibility("default")))
void* ipa_rime_read_candidates(uint64_t session, const char* expected_input, int limit, uint32_t* size) noexcept {
    if (!size) return nullptr;
    *size = 0;
    try {
        if (!expected_input || limit < 1 || limit > static_cast<int>(kMaxCandidates))
            return packet(invalid_argument, 0, {}, size);
        const size_t input_bytes = strnlen(expected_input, 257);
        if (input_bytes > 256) return packet(invalid_argument, 0, {}, size);
        RimeApi* api = rime_get_api();
        if (!RIME_API_AVAILABLE(api, get_input) || !RIME_API_AVAILABLE(api, candidate_list_end) ||
            !RIME_API_AVAILABLE(api, candidate_list_begin) ||
            !RIME_API_AVAILABLE(api, find_session)) return packet(unsupported_api, 0, {}, size);
        if (!session || !api->find_session(session)) return packet(no_session, 0, {}, size);
        auto native_session = rime::Service::instance().GetSession(session);
        auto* context = native_session ? native_session->context() : nullptr;
        if (!context) return packet(no_session, 0, {}, size);
        const char* actual = api->get_input(session);
        const size_t retained_bytes = actual ? strnlen(actual, 257) : 257;
        if (retained_bytes > input_bytes || std::strcmp(actual, expected_input + input_bytes - retained_bytes) != 0)
            return packet(input_mismatch, static_cast<uint32_t>(input_bytes), {}, size);
        const uint32_t committed_input_bytes = static_cast<uint32_t>(input_bytes - retained_bytes);
        // A leading Pinyin quote can already have committed before the remaining spelling.
        // Read that real output without draining it. Ignored Zhuyin tones must still stay literal.
        if (committed_input_bytes) {
            char schema[128]{};
            if (!RIME_API_AVAILABLE(api, get_current_schema) || !api->get_current_schema(session, schema, sizeof(schema)))
                return packet(unsupported_api, 0, {}, size);
            if (std::strcmp(schema, "bopomofo") == 0 || native_session->commit_text().empty())
                return packet(input_mismatch, static_cast<uint32_t>(input_bytes), {}, size);
        }

        IteratorGuard guard{api};
        guard.active = api->candidate_list_begin(session, &guard.iterator);
        if (!guard.active) return packet(ok, static_cast<uint32_t>(input_bytes), {}, size);
        // Read the complete composition to retain already selected prefix segments (e.g. a quote).
        // All declarations come from the pinned upstream headers; no object offsets are guessed.
        if (context->composition().empty()) return packet(native_failure, 0, {}, size);
        const auto& composition = context->composition();
        auto* menu = composition.back().menu.get();
        if (!menu || menu != guard.iterator.ptr) return packet(native_failure, 0, {}, size);
        const auto prefix_end = composition.back().start;
        size_t covered = 0;
        std::string prefix = native_session->commit_text();
        for (size_t i = 0; i + 1 < composition.size(); ++i) {
            const auto& segment = composition[i];
            if (segment.start != covered) return packet(native_failure, 0, {}, size);
            if (auto selected = segment.GetSelectedCandidate()) {
                if (selected->start() != covered || selected->end() > retained_bytes)
                    return packet(native_failure, 0, {}, size);
                prefix += selected->text();
                covered = selected->end();
            } else {
                if (segment.end < covered || segment.end > retained_bytes)
                    return packet(native_failure, 0, {}, size);
                if (!segment.HasTag("phony")) prefix.append(actual + covered, segment.end - covered);
                covered = segment.end;
            }
        }
        if (covered != prefix_end || prefix_end > retained_bytes) return packet(native_failure, 0, {}, size);
        std::vector<Row> rows;
        rows.reserve(limit);
        for (int i = 0; i < limit; ++i) {
            auto candidate = menu->GetCandidateAt(i);
            if (!candidate) break;
            if (prefix.size() + candidate->text().size() > kMaxTextBytes) return packet(oversized, 0, {}, size);
            if (candidate->start() > candidate->end() || candidate->end() > retained_bytes)
                return packet(native_failure, 0, {}, size);
            rows.push_back({static_cast<uint32_t>(i), static_cast<uint32_t>(candidate->start()),
                            static_cast<uint32_t>(candidate->end()), prefix + candidate->text()});
        }
        // Merely enumerate: never select/highlight/commit a candidate or modify input.
        return packet(ok, static_cast<uint32_t>(retained_bytes), rows, size, static_cast<uint32_t>(prefix_end), committed_input_bytes);
    } catch (...) {
        return packet(native_failure, 0, {}, size);
    }
}

extern "C" __attribute__((visibility("default")))
void ipa_rime_free_candidates(void* memory) noexcept { std::free(memory); }
