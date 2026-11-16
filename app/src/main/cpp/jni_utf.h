#pragma once

#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>

namespace transvibe::unicode {

inline void appendUtf8(std::string& out, uint32_t cp) {
    if (cp <= 0x7F) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        if (cp >= 0xD800 && cp <= 0xDFFF) throw std::runtime_error("Invalid Unicode surrogate code point");
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0x10FFFF) {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        throw std::runtime_error("Unicode code point out of range");
    }
}

inline std::string utf16ToUtf8(std::u16string_view input) {
    std::string out;
    out.reserve(input.size() * 2);
    for (size_t i = 0; i < input.size(); ++i) {
        uint32_t cp = input[i];
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (++i >= input.size()) throw std::runtime_error("Unpaired high surrogate");
            const uint32_t low = input[i];
            if (low < 0xDC00 || low > 0xDFFF) throw std::runtime_error("Invalid surrogate pair");
            cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            throw std::runtime_error("Unpaired low surrogate");
        }
        appendUtf8(out, cp);
    }
    return out;
}

inline std::u16string utf8ToUtf16(std::string_view input) {
    std::u16string out;
    out.reserve(input.size());
    for (size_t i = 0; i < input.size();) {
        const uint8_t lead = static_cast<uint8_t>(input[i++]);
        uint32_t cp = 0;
        int continuation = 0;
        uint32_t minimum = 0;
        if (lead <= 0x7F) {
            cp = lead;
        } else if ((lead & 0xE0) == 0xC0) {
            cp = lead & 0x1F; continuation = 1; minimum = 0x80;
        } else if ((lead & 0xF0) == 0xE0) {
            cp = lead & 0x0F; continuation = 2; minimum = 0x800;
        } else if ((lead & 0xF8) == 0xF0) {
            cp = lead & 0x07; continuation = 3; minimum = 0x10000;
        } else {
            throw std::runtime_error("Invalid UTF-8 leading byte");
        }
        for (int j = 0; j < continuation; ++j) {
            if (i >= input.size()) throw std::runtime_error("Truncated UTF-8 sequence");
            const uint8_t next = static_cast<uint8_t>(input[i++]);
            if ((next & 0xC0) != 0x80) throw std::runtime_error("Invalid UTF-8 continuation byte");
            cp = (cp << 6) | (next & 0x3F);
        }
        if (continuation && cp < minimum) throw std::runtime_error("Overlong UTF-8 sequence");
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) throw std::runtime_error("Invalid UTF-8 code point");
        if (cp <= 0xFFFF) {
            out.push_back(static_cast<char16_t>(cp));
        } else {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return out;
}

} // namespace transvibe::unicode
