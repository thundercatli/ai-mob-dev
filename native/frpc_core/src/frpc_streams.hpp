#ifndef AIDEVMOB_FRPC_STREAMS_HPP
#define AIDEVMOB_FRPC_STREAMS_HPP

#include "frpc_crypto.hpp"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace aidevmob::frpc {

class ByteStream {
public:
    virtual ~ByteStream() = default;
    virtual bool writeBytes(const uint8_t* data, std::size_t length, std::string& error) = 0;
    virtual ssize_t readBytes(uint8_t* data, std::size_t length, std::string& error) = 0;
    virtual void closeWrite() = 0;
};

class CryptoStream final : public ByteStream {
public:
    CryptoStream(ByteStream& stream, const std::string& secret);

    bool writeBytes(const uint8_t* data, std::size_t length, std::string& error) override;
    ssize_t readBytes(uint8_t* data, std::size_t length, std::string& error) override;
    void closeWrite() override;

private:
    ByteStream& stream_;
    std::array<uint8_t, 16> key_;
    std::unique_ptr<AesCfb128> encryptor_;
    std::unique_ptr<AesCfb128> decryptor_;
};

class SnappyStream final : public ByteStream {
public:
    explicit SnappyStream(ByteStream& stream);

    bool writeBytes(const uint8_t* data, std::size_t length, std::string& error) override;
    ssize_t readBytes(uint8_t* data, std::size_t length, std::string& error) override;
    void closeWrite() override;

private:
    bool readNextChunk(std::string& error, bool& eof);

    ByteStream& stream_;
    bool headerWritten_ = false;
    bool headerRead_ = false;
    std::vector<uint8_t> pending_;
    std::size_t pendingOffset_ = 0;
};

}  // namespace aidevmob::frpc

#endif
