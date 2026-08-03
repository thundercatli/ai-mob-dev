#include "frpc_streams.hpp"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

namespace {

class MemoryStream final : public aidevmob::frpc::ByteStream {
public:
    bool writeBytes(const uint8_t* data, std::size_t length, std::string&) override {
        bytes.insert(bytes.end(), data, data + length);
        return true;
    }

    ssize_t readBytes(uint8_t* data, std::size_t length, std::string&) override {
        if (readOffset == bytes.size()) return 0;
        const std::size_t count = std::min({length, bytes.size() - readOffset, maxReadSize});
        std::memcpy(data, bytes.data() + readOffset, count);
        readOffset += count;
        return static_cast<ssize_t>(count);
    }

    void closeWrite() override {}

    std::vector<uint8_t> bytes;
    std::size_t readOffset = 0;
    std::size_t maxReadSize = 11;
};

std::vector<uint8_t> fromHex(const std::string& hex) {
    std::vector<uint8_t> output;
    output.reserve(hex.size() / 2);
    for (std::size_t index = 0; index < hex.size(); index += 2) {
        output.push_back(static_cast<uint8_t>(std::stoul(hex.substr(index, 2), nullptr, 16)));
    }
    return output;
}

bool readAll(aidevmob::frpc::ByteStream& stream, std::vector<uint8_t>& output) {
    std::vector<uint8_t> buffer(37);
    std::string error;
    while (true) {
        const ssize_t count = stream.readBytes(buffer.data(), buffer.size(), error);
        if (count == 0) return true;
        if (count < 0) {
            std::cerr << error << '\n';
            return false;
        }
        output.insert(output.end(), buffer.begin(), buffer.begin() + count);
    }
}

std::vector<uint8_t> repeatedPayload() {
    const std::string unit = "hello-frpc-stcp-";
    std::vector<uint8_t> output;
    for (int index = 0; index < 128; ++index) output.insert(output.end(), unit.begin(), unit.end());
    return output;
}

bool testOfficialSnappyDecode() {
    MemoryStream memory;
    memory.bytes = fromHex(
        "ff060000734e61507059007700005eb8a87b80103c68656c6c6f2d667270632d737463702d"
        "fe1000fe1000fe1000fe1000fe1000fe1000fe1000fe1000fe1000fe1000fe1000fe1000"
        "fe1000fe1000fe1000fe1000fe1000fe1000fe1000fe1000fe1000fe1000fe1000fe1000"
        "fe1000fe1000fe1000fe1000fe1000fe1000fe1000be1000"
    );
    aidevmob::frpc::SnappyStream stream(memory);
    std::vector<uint8_t> decoded;
    return readAll(stream, decoded) && decoded == repeatedPayload();
}

bool testSnappyRoundTrip() {
    MemoryStream memory;
    aidevmob::frpc::SnappyStream writer(memory);
    const std::vector<uint8_t> payload = repeatedPayload();
    std::string error;
    if (!writer.writeBytes(payload.data(), 17, error) ||
        !writer.writeBytes(payload.data() + 17, payload.size() - 17, error)) {
        std::cerr << error << '\n';
        return false;
    }
    aidevmob::frpc::SnappyStream reader(memory);
    std::vector<uint8_t> decoded;
    return readAll(reader, decoded) && decoded == payload;
}

bool testCryptoRoundTrip() {
    MemoryStream memory;
    aidevmob::frpc::CryptoStream writer(memory, "shared-secret");
    const std::vector<uint8_t> payload = repeatedPayload();
    std::string error;
    if (!writer.writeBytes(payload.data(), 31, error) ||
        !writer.writeBytes(payload.data() + 31, payload.size() - 31, error)) {
        std::cerr << error << '\n';
        return false;
    }
    aidevmob::frpc::CryptoStream reader(memory, "shared-secret");
    std::vector<uint8_t> decoded;
    return readAll(reader, decoded) && decoded == payload;
}

}  // namespace

int main() {
    if (!testOfficialSnappyDecode()) {
        std::cerr << "official Snappy decode failed\n";
        return EXIT_FAILURE;
    }
    if (!testSnappyRoundTrip()) {
        std::cerr << "Snappy round trip failed\n";
        return EXIT_FAILURE;
    }
    if (!testCryptoRoundTrip()) {
        std::cerr << "crypto round trip failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "FRPC stream compatibility tests passed\n";
    return EXIT_SUCCESS;
}
