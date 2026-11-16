#include "../jni_utf.h"
#include "../whisper_metric_utils.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <string>
#include <vector>

int main() {
    // UTF-8 literal element type is char in C++17 and char8_t in C++20.
    // Preserve the literal bytes without relying on implicit char8_t conversion.
    auto utf8 = [](const auto* units) { return std::string(reinterpret_cast<const char*>(units)); };
    const std::vector<std::string> cases = {
        utf8(u8"مرحبا بالعالم"),
        "Hello world",
        utf8(u8"مرحبا Hello"),
        utf8(u8"🎬🔥"),
        utf8(u8"𐍈 𝄞 𠀋"),
        utf8(u8"العربية English हिन्दी 中文 🎧")
    };
    for (const auto& original : cases) {
        const auto utf16 = transvibe::unicode::utf8ToUtf16(original);
        const auto roundTrip = transvibe::unicode::utf16ToUtf8(utf16);
        assert(roundTrip == original);
    }

    const auto confidence = transvibe::metrics::meanValidTokenProbability({0.5f, 0.75f, 1.0f});
    assert(confidence.has_value());
    assert(std::fabs(*confidence - 0.75f) < 0.00001f);
    assert(!transvibe::metrics::meanValidTokenProbability({-1.0f, NAN, 1.5f}).has_value());
    assert(transvibe::metrics::validProbability(0.2f).has_value());
    assert(!transvibe::metrics::validProbability(-0.1f).has_value());
    assert(!transvibe::metrics::validProbability(1.1f).has_value());

    std::cout << "JNI Unicode + metric helper tests passed\n";
    return 0;
}
