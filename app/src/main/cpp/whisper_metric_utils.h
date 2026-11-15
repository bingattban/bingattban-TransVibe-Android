#pragma once

#include <cmath>
#include <optional>
#include <vector>

namespace transvibe::metrics {

inline std::optional<float> meanValidTokenProbability(const std::vector<float>& probabilities) {
    double sum = 0.0;
    int count = 0;
    for (float p : probabilities) {
        if (std::isfinite(p) && p >= 0.0f && p <= 1.0f) {
            sum += p;
            ++count;
        }
    }
    if (count == 0) return std::nullopt;
    return static_cast<float>(sum / count);
}

inline std::optional<float> validProbability(float value) {
    if (!std::isfinite(value) || value < 0.0f || value > 1.0f) return std::nullopt;
    return value;
}

} // namespace transvibe::metrics
