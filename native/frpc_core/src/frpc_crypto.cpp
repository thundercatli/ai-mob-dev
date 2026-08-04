#include "frpc_crypto.hpp"

#include <algorithm>
#include <array>
#include <cstring>
#include <random>
#include <vector>

namespace aidevmob::frpc {
namespace {

uint32_t rotateLeft(uint32_t value, uint32_t count) {
    return (value << count) | (value >> (32U - count));
}

std::array<uint8_t, 20> sha1(const uint8_t* data, std::size_t length) {
    std::vector<uint8_t> message(data, data + length);
    const uint64_t bitLength = static_cast<uint64_t>(length) * 8U;
    message.push_back(0x80);
    while ((message.size() % 64U) != 56U) message.push_back(0);
    for (int index = 7; index >= 0; --index) {
        message.push_back(static_cast<uint8_t>((bitLength >> (index * 8)) & 0xffU));
    }

    uint32_t h0 = 0x67452301;
    uint32_t h1 = 0xefcdab89;
    uint32_t h2 = 0x98badcfe;
    uint32_t h3 = 0x10325476;
    uint32_t h4 = 0xc3d2e1f0;

    for (std::size_t offset = 0; offset < message.size(); offset += 64) {
        std::array<uint32_t, 80> words{};
        for (int index = 0; index < 16; ++index) {
            const std::size_t position = offset + static_cast<std::size_t>(index) * 4U;
            words[index] = (static_cast<uint32_t>(message[position]) << 24U) |
                (static_cast<uint32_t>(message[position + 1]) << 16U) |
                (static_cast<uint32_t>(message[position + 2]) << 8U) |
                static_cast<uint32_t>(message[position + 3]);
        }
        for (int index = 16; index < 80; ++index) {
            words[index] = rotateLeft(
                words[index - 3] ^ words[index - 8] ^ words[index - 14] ^ words[index - 16],
                1
            );
        }

        uint32_t a = h0;
        uint32_t b = h1;
        uint32_t c = h2;
        uint32_t d = h3;
        uint32_t e = h4;
        for (int index = 0; index < 80; ++index) {
            uint32_t function;
            uint32_t constant;
            if (index < 20) {
                function = (b & c) | ((~b) & d);
                constant = 0x5a827999;
            } else if (index < 40) {
                function = b ^ c ^ d;
                constant = 0x6ed9eba1;
            } else if (index < 60) {
                function = (b & c) | (b & d) | (c & d);
                constant = 0x8f1bbcdc;
            } else {
                function = b ^ c ^ d;
                constant = 0xca62c1d6;
            }
            const uint32_t temporary = rotateLeft(a, 5) + function + e + constant + words[index];
            e = d;
            d = c;
            c = rotateLeft(b, 30);
            b = a;
            a = temporary;
        }
        h0 += a;
        h1 += b;
        h2 += c;
        h3 += d;
        h4 += e;
    }

    std::array<uint8_t, 20> digest{};
    const std::array<uint32_t, 5> hashes{h0, h1, h2, h3, h4};
    for (std::size_t index = 0; index < hashes.size(); ++index) {
        digest[index * 4] = static_cast<uint8_t>(hashes[index] >> 24U);
        digest[index * 4 + 1] = static_cast<uint8_t>(hashes[index] >> 16U);
        digest[index * 4 + 2] = static_cast<uint8_t>(hashes[index] >> 8U);
        digest[index * 4 + 3] = static_cast<uint8_t>(hashes[index]);
    }
    return digest;
}

std::array<uint8_t, 20> hmacSha1(const std::vector<uint8_t>& key, const std::vector<uint8_t>& data) {
    std::array<uint8_t, 64> normalized{};
    if (key.size() > normalized.size()) {
        const auto digest = sha1(key.data(), key.size());
        std::copy(digest.begin(), digest.end(), normalized.begin());
    } else {
        std::copy(key.begin(), key.end(), normalized.begin());
    }
    std::vector<uint8_t> inner(normalized.size() + data.size());
    std::vector<uint8_t> outer(normalized.size() + 20);
    for (std::size_t index = 0; index < normalized.size(); ++index) {
        inner[index] = normalized[index] ^ 0x36U;
        outer[index] = normalized[index] ^ 0x5cU;
    }
    std::copy(data.begin(), data.end(), inner.begin() + normalized.size());
    const auto innerDigest = sha1(inner.data(), inner.size());
    std::copy(innerDigest.begin(), innerDigest.end(), outer.begin() + normalized.size());
    return sha1(outer.data(), outer.size());
}

constexpr std::array<uint8_t, 256> sbox = {
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
    0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
    0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
    0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
    0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
    0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
    0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
    0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
    0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
};

uint8_t xtime(uint8_t value) {
    return static_cast<uint8_t>((value << 1U) ^ ((value & 0x80U) ? 0x1bU : 0));
}

void addRoundKey(uint8_t* state, const uint8_t* roundKey) {
    for (int index = 0; index < 16; ++index) state[index] ^= roundKey[index];
}

void subBytes(uint8_t* state) {
    for (int index = 0; index < 16; ++index) state[index] = sbox[state[index]];
}

void shiftRows(uint8_t* state) {
    const std::array<uint8_t, 16> copy = {
        state[0], state[5], state[10], state[15],
        state[4], state[9], state[14], state[3],
        state[8], state[13], state[2], state[7],
        state[12], state[1], state[6], state[11]
    };
    std::copy(copy.begin(), copy.end(), state);
}

void mixColumns(uint8_t* state) {
    for (int column = 0; column < 4; ++column) {
        uint8_t* values = state + column * 4;
        const uint8_t sum = values[0] ^ values[1] ^ values[2] ^ values[3];
        const uint8_t first = values[0];
        values[0] ^= sum ^ xtime(values[0] ^ values[1]);
        values[1] ^= sum ^ xtime(values[1] ^ values[2]);
        values[2] ^= sum ^ xtime(values[2] ^ values[3]);
        values[3] ^= sum ^ xtime(values[3] ^ first);
    }
}

}  // namespace

std::array<uint8_t, 16> deriveLegacyKey(const std::string& secret) {
    const std::vector<uint8_t> password(secret.begin(), secret.end());
    const std::vector<uint8_t> salt{'f', 'r', 'p'};
    std::vector<uint8_t> saltBlock = salt;
    saltBlock.insert(saltBlock.end(), {0, 0, 0, 1});
    auto value = hmacSha1(password, saltBlock);
    auto output = value;
    for (int iteration = 1; iteration < 64; ++iteration) {
        const std::vector<uint8_t> previous(value.begin(), value.end());
        value = hmacSha1(password, previous);
        for (std::size_t index = 0; index < output.size(); ++index) output[index] ^= value[index];
    }
    std::array<uint8_t, 16> key{};
    std::copy_n(output.begin(), key.size(), key.begin());
    return key;
}

bool fillRandom(uint8_t* output, std::size_t length) {
    try {
        std::random_device random;
        for (std::size_t index = 0; index < length; ++index) {
            output[index] = static_cast<uint8_t>(random());
        }
        return true;
    } catch (...) {
        return false;
    }
}

AesCfb128::AesCfb128(
    const std::array<uint8_t, 16>& key,
    const std::array<uint8_t, 16>& iv,
    bool encrypt
) : feedback_(iv), encrypt_(encrypt) {
    expandKey(key);
}

void AesCfb128::expandKey(const std::array<uint8_t, 16>& key) {
    std::copy(key.begin(), key.end(), roundKey_.begin());
    uint8_t rcon = 1;
    std::size_t generated = 16;
    std::array<uint8_t, 4> temporary{};
    while (generated < roundKey_.size()) {
        std::copy_n(roundKey_.begin() + generated - 4, 4, temporary.begin());
        if (generated % 16 == 0) {
            const uint8_t first = temporary[0];
            temporary[0] = sbox[temporary[1]] ^ rcon;
            temporary[1] = sbox[temporary[2]];
            temporary[2] = sbox[temporary[3]];
            temporary[3] = sbox[first];
            rcon = xtime(rcon);
        }
        for (int index = 0; index < 4; ++index) {
            roundKey_[generated] = roundKey_[generated - 16] ^ temporary[index];
            ++generated;
        }
    }
}

void AesCfb128::encryptBlock(const uint8_t* input, uint8_t* output) const {
    std::array<uint8_t, 16> state{};
    std::copy_n(input, state.size(), state.begin());
    addRoundKey(state.data(), roundKey_.data());
    for (int round = 1; round < 10; ++round) {
        subBytes(state.data());
        shiftRows(state.data());
        mixColumns(state.data());
        addRoundKey(state.data(), roundKey_.data() + round * 16);
    }
    subBytes(state.data());
    shiftRows(state.data());
    addRoundKey(state.data(), roundKey_.data() + 160);
    std::copy(state.begin(), state.end(), output);
}

void AesCfb128::process(uint8_t* data, std::size_t length) {
    for (std::size_t index = 0; index < length; ++index) {
        if (streamOffset_ == keyStream_.size()) {
            encryptBlock(feedback_.data(), keyStream_.data());
            streamOffset_ = 0;
        }
        const uint8_t input = data[index];
        const uint8_t output = input ^ keyStream_[streamOffset_];
        data[index] = output;
        feedback_[streamOffset_] = encrypt_ ? output : input;
        ++streamOffset_;
    }
}

}  // namespace aidevmob::frpc
