#ifndef AIDEVMOB_FRPC_CRYPTO_HPP
#define AIDEVMOB_FRPC_CRYPTO_HPP

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace aidevmob::frpc {

std::array<uint8_t, 16> deriveLegacyKey(const std::string& secret);
bool fillRandom(uint8_t* output, std::size_t length);

class AesCfb128 {
public:
    AesCfb128(const std::array<uint8_t, 16>& key, const std::array<uint8_t, 16>& iv, bool encrypt);
    void process(uint8_t* data, std::size_t length);

private:
    void expandKey(const std::array<uint8_t, 16>& key);
    void encryptBlock(const uint8_t* input, uint8_t* output) const;

    std::array<uint8_t, 176> roundKey_{};
    std::array<uint8_t, 16> feedback_{};
    std::array<uint8_t, 16> keyStream_{};
    std::size_t streamOffset_ = 16;
    bool encrypt_;
};

}  // namespace aidevmob::frpc

#endif
