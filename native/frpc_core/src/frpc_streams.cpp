#include "frpc_streams.hpp"

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>

namespace aidevmob::frpc {
namespace {

constexpr std::size_t kSnappyMaxBlockSize = 65536;
constexpr std::array<uint8_t, 6> kSnappyStreamIdentifier{'s', 'N', 'a', 'P', 'p', 'Y'};

enum class ReadExactResult { OK, EOF_STREAM, ERROR };

ReadExactResult readExact(
    ByteStream& stream,
    uint8_t* data,
    std::size_t length,
    std::string& error
) {
    std::size_t offset = 0;
    while (offset < length) {
        const ssize_t count = stream.readBytes(data + offset, length - offset, error);
        if (count > 0) {
            offset += static_cast<std::size_t>(count);
        } else if (count == 0) {
            if (offset == 0) return ReadExactResult::EOF_STREAM;
            error = "unexpected end of stream";
            return ReadExactResult::ERROR;
        } else {
            return ReadExactResult::ERROR;
        }
    }
    return ReadExactResult::OK;
}

uint32_t crc32c(const uint8_t* data, std::size_t length) {
    uint32_t crc = 0xffffffffU;
    for (std::size_t index = 0; index < length; ++index) {
        crc ^= data[index];
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc >> 1U) ^ ((crc & 1U) != 0 ? 0x82f63b78U : 0U);
        }
    }
    return ~crc;
}

uint32_t maskedCrc32c(const uint8_t* data, std::size_t length) {
    const uint32_t crc = crc32c(data, length);
    return ((crc >> 15U) | (crc << 17U)) + 0xa282ead8U;
}

void appendLittle32(std::vector<uint8_t>& output, uint32_t value) {
    output.push_back(static_cast<uint8_t>(value));
    output.push_back(static_cast<uint8_t>(value >> 8U));
    output.push_back(static_cast<uint8_t>(value >> 16U));
    output.push_back(static_cast<uint8_t>(value >> 24U));
}

uint32_t readLittle32(const uint8_t* data) {
    return static_cast<uint32_t>(data[0]) |
        (static_cast<uint32_t>(data[1]) << 8U) |
        (static_cast<uint32_t>(data[2]) << 16U) |
        (static_cast<uint32_t>(data[3]) << 24U);
}

bool appendCopy(
    std::vector<uint8_t>& output,
    std::size_t offset,
    std::size_t length,
    std::string& error
) {
    if (offset == 0 || offset > output.size() || length > kSnappyMaxBlockSize - output.size()) {
        error = "invalid snappy copy";
        return false;
    }
    for (std::size_t index = 0; index < length; ++index) {
        output.push_back(output[output.size() - offset]);
    }
    return true;
}

bool decodeRawSnappy(
    const uint8_t* input,
    std::size_t inputLength,
    std::vector<uint8_t>& output,
    std::string& error
) {
    std::size_t position = 0;
    uint64_t decodedLength = 0;
    uint32_t shift = 0;
    while (position < inputLength && shift <= 63) {
        const uint8_t byte = input[position++];
        decodedLength |= static_cast<uint64_t>(byte & 0x7fU) << shift;
        if ((byte & 0x80U) == 0) break;
        shift += 7;
    }
    if (position == 0 || position > inputLength || shift > 63 || decodedLength > kSnappyMaxBlockSize) {
        error = "invalid snappy decoded length";
        return false;
    }
    output.clear();
    output.reserve(static_cast<std::size_t>(decodedLength));

    while (position < inputLength) {
        const uint8_t tag = input[position++];
        const uint8_t type = tag & 0x03U;
        if (type == 0) {
            uint64_t literalLength = tag >> 2U;
            if (literalLength < 60) {
                ++literalLength;
            } else {
                const std::size_t byteCount = static_cast<std::size_t>(literalLength - 59);
                if (byteCount > 4 || position + byteCount > inputLength) {
                    error = "invalid snappy literal length";
                    return false;
                }
                literalLength = 0;
                for (std::size_t index = 0; index < byteCount; ++index) {
                    literalLength |= static_cast<uint64_t>(input[position++]) << (index * 8U);
                }
                ++literalLength;
            }
            if (literalLength > inputLength - position ||
                literalLength > decodedLength - output.size()) {
                error = "snappy literal exceeds block";
                return false;
            }
            output.insert(
                output.end(),
                input + position,
                input + position + static_cast<std::size_t>(literalLength)
            );
            position += static_cast<std::size_t>(literalLength);
            continue;
        }

        std::size_t copyLength = 0;
        std::size_t copyOffset = 0;
        if (type == 1) {
            if (position >= inputLength) {
                error = "truncated snappy copy-1";
                return false;
            }
            copyLength = 4U + ((tag >> 2U) & 0x07U);
            copyOffset = (static_cast<std::size_t>(tag & 0xe0U) << 3U) | input[position++];
        } else if (type == 2) {
            if (position + 2 > inputLength) {
                error = "truncated snappy copy-2";
                return false;
            }
            copyLength = 1U + (tag >> 2U);
            copyOffset = input[position] | (static_cast<std::size_t>(input[position + 1]) << 8U);
            position += 2;
        } else {
            if (position + 4 > inputLength) {
                error = "truncated snappy copy-4";
                return false;
            }
            copyLength = 1U + (tag >> 2U);
            copyOffset = readLittle32(input + position);
            position += 4;
        }
        if (!appendCopy(output, copyOffset, copyLength, error)) return false;
    }
    if (output.size() != decodedLength) {
        error = "snappy decoded length mismatch";
        return false;
    }
    return true;
}

}  // namespace

CryptoStream::CryptoStream(ByteStream& stream, const std::string& secret)
    : stream_(stream), key_(deriveLegacyKey(secret)) {}

bool CryptoStream::writeBytes(const uint8_t* data, std::size_t length, std::string& error) {
    if (encryptor_ == nullptr) {
        std::array<uint8_t, 16> iv{};
        if (!fillRandom(iv.data(), iv.size())) {
            error = "generate crypto IV failed";
            return false;
        }
        if (!stream_.writeBytes(iv.data(), iv.size(), error)) return false;
        encryptor_ = std::make_unique<AesCfb128>(key_, iv, true);
    }
    std::vector<uint8_t> encrypted(data, data + length);
    encryptor_->process(encrypted.data(), encrypted.size());
    return stream_.writeBytes(encrypted.data(), encrypted.size(), error);
}

ssize_t CryptoStream::readBytes(uint8_t* data, std::size_t length, std::string& error) {
    if (decryptor_ == nullptr) {
        std::array<uint8_t, 16> iv{};
        const ReadExactResult result = readExact(stream_, iv.data(), iv.size(), error);
        if (result == ReadExactResult::EOF_STREAM) return 0;
        if (result == ReadExactResult::ERROR) return -1;
        decryptor_ = std::make_unique<AesCfb128>(key_, iv, false);
    }
    const ssize_t count = stream_.readBytes(data, length, error);
    if (count > 0) decryptor_->process(data, static_cast<std::size_t>(count));
    return count;
}

void CryptoStream::closeWrite() {
    stream_.closeWrite();
}

SnappyStream::SnappyStream(ByteStream& stream) : stream_(stream) {}

bool SnappyStream::writeBytes(const uint8_t* data, std::size_t length, std::string& error) {
    std::vector<uint8_t> framed;
    if (!headerWritten_) {
        framed.insert(framed.end(), {0xff, 0x06, 0x00, 0x00});
        framed.insert(framed.end(), kSnappyStreamIdentifier.begin(), kSnappyStreamIdentifier.end());
        headerWritten_ = true;
    }
    std::size_t position = 0;
    while (position < length) {
        const std::size_t count = std::min(kSnappyMaxBlockSize, length - position);
        const uint32_t chunkLength = static_cast<uint32_t>(count + 4);
        framed.push_back(0x01);
        framed.push_back(static_cast<uint8_t>(chunkLength));
        framed.push_back(static_cast<uint8_t>(chunkLength >> 8U));
        framed.push_back(static_cast<uint8_t>(chunkLength >> 16U));
        appendLittle32(framed, maskedCrc32c(data + position, count));
        framed.insert(framed.end(), data + position, data + position + count);
        position += count;
    }
    return stream_.writeBytes(framed.data(), framed.size(), error);
}

ssize_t SnappyStream::readBytes(uint8_t* data, std::size_t length, std::string& error) {
    while (pendingOffset_ == pending_.size()) {
        pending_.clear();
        pendingOffset_ = 0;
        bool eof = false;
        if (!readNextChunk(error, eof)) return -1;
        if (eof) return 0;
    }
    const std::size_t count = std::min(length, pending_.size() - pendingOffset_);
    std::memcpy(data, pending_.data() + pendingOffset_, count);
    pendingOffset_ += count;
    return static_cast<ssize_t>(count);
}

void SnappyStream::closeWrite() {
    stream_.closeWrite();
}

bool SnappyStream::readNextChunk(std::string& error, bool& eof) {
    eof = false;
    while (true) {
        std::array<uint8_t, 4> header{};
        const ReadExactResult headerResult = readExact(stream_, header.data(), header.size(), error);
        if (headerResult == ReadExactResult::EOF_STREAM) {
            eof = true;
            return true;
        }
        if (headerResult == ReadExactResult::ERROR) return false;
        const uint8_t type = header[0];
        const std::size_t length = static_cast<std::size_t>(header[1]) |
            (static_cast<std::size_t>(header[2]) << 8U) |
            (static_cast<std::size_t>(header[3]) << 16U);
        if (length > kSnappyMaxBlockSize + 4) {
            error = "snappy framed chunk exceeds limit";
            return false;
        }
        std::vector<uint8_t> chunk(length);
        if (readExact(stream_, chunk.data(), chunk.size(), error) != ReadExactResult::OK) return false;
        if (type == 0xff) {
            if (chunk.size() != kSnappyStreamIdentifier.size() ||
                !std::equal(chunk.begin(), chunk.end(), kSnappyStreamIdentifier.begin())) {
                error = "invalid snappy stream identifier";
                return false;
            }
            headerRead_ = true;
            continue;
        }
        if (!headerRead_) {
            error = "snappy data before stream identifier";
            return false;
        }
        if (type >= 0x80 && type <= 0xfe) continue;
        if (type != 0x00 && type != 0x01) {
            error = "unsupported snappy chunk type";
            return false;
        }
        if (chunk.size() < 4) {
            error = "truncated snappy checksum";
            return false;
        }
        if (type == 0x01) {
            pending_.assign(chunk.begin() + 4, chunk.end());
        } else if (!decodeRawSnappy(chunk.data() + 4, chunk.size() - 4, pending_, error)) {
            return false;
        }
        const uint32_t expectedCrc = readLittle32(chunk.data());
        if (maskedCrc32c(pending_.data(), pending_.size()) != expectedCrc) {
            error = "snappy checksum mismatch";
            return false;
        }
        return true;
    }
}

}  // namespace aidevmob::frpc
