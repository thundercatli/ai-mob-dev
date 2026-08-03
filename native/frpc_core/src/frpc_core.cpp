#include "aidevmob/frpc_core.h"
#include "frpc_streams.hpp"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr std::size_t kMaxMessageSize = 10240;
constexpr uint32_t kYamuxInitialWindow = 256U * 1024U;
constexpr uint32_t kYamuxStreamId = 1;
constexpr uint8_t kYamuxData = 0;
constexpr uint8_t kYamuxWindowUpdate = 1;
constexpr uint8_t kYamuxPing = 2;
constexpr uint8_t kYamuxGoAway = 3;
constexpr uint16_t kYamuxSyn = 1;
constexpr uint16_t kYamuxAck = 2;
constexpr uint16_t kYamuxFin = 4;
constexpr uint16_t kYamuxRst = 8;

uint32_t rotateLeft(uint32_t value, uint32_t count) {
    return (value << count) | (value >> (32U - count));
}

std::string md5Hex(const std::string& input) {
    static constexpr std::array<uint32_t, 64> shifts = {
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    };
    static constexpr std::array<uint32_t, 64> constants = {
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a,
        0xa8304613, 0xfd469501, 0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
        0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821, 0xf61e2562, 0xc040b340,
        0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
        0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8,
        0x676f02d9, 0x8d2a4c8a, 0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
        0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70, 0x289b7ec6, 0xeaa127fa,
        0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
        0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92,
        0xffeff47d, 0x85845dd1, 0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
        0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
    };

    std::vector<uint8_t> bytes(input.begin(), input.end());
    const uint64_t bitLength = static_cast<uint64_t>(bytes.size()) * 8U;
    bytes.push_back(0x80);
    while ((bytes.size() % 64U) != 56U) {
        bytes.push_back(0);
    }
    for (int index = 0; index < 8; ++index) {
        bytes.push_back(static_cast<uint8_t>((bitLength >> (index * 8)) & 0xffU));
    }

    uint32_t a0 = 0x67452301;
    uint32_t b0 = 0xefcdab89;
    uint32_t c0 = 0x98badcfe;
    uint32_t d0 = 0x10325476;

    for (std::size_t offset = 0; offset < bytes.size(); offset += 64) {
        std::array<uint32_t, 16> words{};
        for (std::size_t index = 0; index < words.size(); ++index) {
            const std::size_t position = offset + index * 4;
            words[index] = static_cast<uint32_t>(bytes[position]) |
                (static_cast<uint32_t>(bytes[position + 1]) << 8U) |
                (static_cast<uint32_t>(bytes[position + 2]) << 16U) |
                (static_cast<uint32_t>(bytes[position + 3]) << 24U);
        }

        uint32_t a = a0;
        uint32_t b = b0;
        uint32_t c = c0;
        uint32_t d = d0;

        for (uint32_t index = 0; index < 64; ++index) {
            uint32_t value;
            uint32_t wordIndex;
            if (index < 16) {
                value = (b & c) | ((~b) & d);
                wordIndex = index;
            } else if (index < 32) {
                value = (d & b) | ((~d) & c);
                wordIndex = (5U * index + 1U) % 16U;
            } else if (index < 48) {
                value = b ^ c ^ d;
                wordIndex = (3U * index + 5U) % 16U;
            } else {
                value = c ^ (b | (~d));
                wordIndex = (7U * index) % 16U;
            }
            value += a + constants[index] + words[wordIndex];
            a = d;
            d = c;
            c = b;
            b += rotateLeft(value, shifts[index]);
        }

        a0 += a;
        b0 += b;
        c0 += c;
        d0 += d;
    }

    std::ostringstream output;
    output << std::hex << std::setfill('0');
    for (uint32_t value : {a0, b0, c0, d0}) {
        for (int index = 0; index < 4; ++index) {
            output << std::setw(2) << ((value >> (index * 8)) & 0xffU);
        }
    }
    return output.str();
}

std::string jsonEscape(const std::string& value) {
    std::ostringstream output;
    for (const unsigned char character : value) {
        switch (character) {
            case '\\': output << "\\\\"; break;
            case '"': output << "\\\""; break;
            case '\b': output << "\\b"; break;
            case '\f': output << "\\f"; break;
            case '\n': output << "\\n"; break;
            case '\r': output << "\\r"; break;
            case '\t': output << "\\t"; break;
            default:
                if (character < 0x20) {
                    output << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                           << static_cast<int>(character) << std::dec;
                } else {
                    output << static_cast<char>(character);
                }
        }
    }
    return output.str();
}

bool writeAll(int socket, const uint8_t* data, std::size_t length) {
    std::size_t written = 0;
    while (written < length) {
#ifdef MSG_NOSIGNAL
        const ssize_t count = send(socket, data + written, length - written, MSG_NOSIGNAL);
#else
        const ssize_t count = send(socket, data + written, length - written, 0);
#endif
        if (count > 0) {
            written += static_cast<std::size_t>(count);
            continue;
        }
        if (count < 0 && errno == EINTR) {
            continue;
        }
        return false;
    }
    return true;
}

bool readAll(int socket, uint8_t* data, std::size_t length) {
    std::size_t readCount = 0;
    while (readCount < length) {
        const ssize_t count = recv(socket, data + readCount, length - readCount, 0);
        if (count > 0) {
            readCount += static_cast<std::size_t>(count);
            continue;
        }
        if (count < 0 && errno == EINTR) {
            continue;
        }
        return false;
    }
    return true;
}

void closeSocket(int socket) {
    if (socket < 0) return;
    shutdown(socket, SHUT_RDWR);
    close(socket);
}

std::string socketError(const std::string& action) {
    return action + ": " + std::strerror(errno);
}

void appendUtf8(std::string& output, uint32_t codePoint) {
    if (codePoint <= 0x7f) {
        output.push_back(static_cast<char>(codePoint));
    } else if (codePoint <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (codePoint >> 6)));
        output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xe0 | (codePoint >> 12)));
        output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
    }
}

int hexValue(char character) {
    if (character >= '0' && character <= '9') return character - '0';
    if (character >= 'a' && character <= 'f') return character - 'a' + 10;
    if (character >= 'A' && character <= 'F') return character - 'A' + 10;
    return -1;
}

bool extractJsonString(const std::string& json, const std::string& key, std::string& value) {
    const std::string marker = "\"" + key + "\"";
    std::size_t position = json.find(marker);
    if (position == std::string::npos) return false;
    position = json.find(':', position + marker.size());
    if (position == std::string::npos) return false;
    position = json.find('"', position + 1);
    if (position == std::string::npos) return false;
    ++position;

    std::string output;
    while (position < json.size()) {
        const char character = json[position++];
        if (character == '"') {
            value = output;
            return true;
        }
        if (character != '\\') {
            output.push_back(character);
            continue;
        }
        if (position >= json.size()) return false;
        const char escaped = json[position++];
        switch (escaped) {
            case '"': output.push_back('"'); break;
            case '\\': output.push_back('\\'); break;
            case '/': output.push_back('/'); break;
            case 'b': output.push_back('\b'); break;
            case 'f': output.push_back('\f'); break;
            case 'n': output.push_back('\n'); break;
            case 'r': output.push_back('\r'); break;
            case 't': output.push_back('\t'); break;
            case 'u': {
                if (position + 4 > json.size()) return false;
                uint32_t codePoint = 0;
                for (int index = 0; index < 4; ++index) {
                    const int digit = hexValue(json[position++]);
                    if (digit < 0) return false;
                    codePoint = (codePoint << 4U) | static_cast<uint32_t>(digit);
                }
                appendUtf8(output, codePoint);
                break;
            }
            default: return false;
        }
    }
    return false;
}

class FrpConnection final : public aidevmob::frpc::ByteStream {
public:
    FrpConnection(int socket, bool yamux) : socket_(socket), yamux_(yamux) {}

    bool writeBytes(const uint8_t* data, std::size_t length, std::string& error) override {
        if (!yamux_) {
            std::lock_guard<std::mutex> lock(writeMutex_);
            if (writeAll(socket_, data, length)) return true;
            error = socketError("write frp stream");
            return false;
        }

        std::size_t written = 0;
        while (written < length) {
            uint32_t count = 0;
            uint16_t flags = 0;
            {
                std::unique_lock<std::mutex> lock(stateMutex_);
                stateChanged_.wait(lock, [this] {
                    return sendWindow_ > 0 || remoteClosed_ || !terminalError_.empty();
                });
                if (!terminalError_.empty()) {
                    error = terminalError_;
                    return false;
                }
                if (remoteClosed_) {
                    error = "yamux stream closed by server";
                    return false;
                }
                count = static_cast<uint32_t>(
                    std::min<std::size_t>(length - written, sendWindow_)
                );
                flags = streamOpened_ ? 0 : kYamuxSyn;
                streamOpened_ = true;
                sendWindow_ -= count;
            }
            if (!writeYamuxFrame(kYamuxData, flags, kYamuxStreamId, count, data + written, error)) {
                fail(error);
                return false;
            }
            written += count;
        }
        return true;
    }

    ssize_t readBytes(uint8_t* data, std::size_t length, std::string& error) override {
        if (!yamux_) {
            while (true) {
                const ssize_t count = recv(socket_, data, length, 0);
                if (count >= 0) return count;
                if (errno != EINTR) {
                    error = socketError("read frp stream");
                    return -1;
                }
            }
        }

        while (true) {
            if (pendingOffset_ < pendingData_.size()) return copyPending(data, length);
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                if (!terminalError_.empty()) {
                    error = terminalError_;
                    return -1;
                }
                if (remoteClosed_) return 0;
            }
            const ProcessResult result = processYamuxFrame(error);
            if (result == ProcessResult::ERROR) return -1;
            if (result == ProcessResult::EOF_STREAM && pendingOffset_ == pendingData_.size()) return 0;
        }
    }

    void closeWrite() override {
        if (!yamux_) {
            shutdown(socket_, SHUT_WR);
            return;
        }
        uint16_t flags = 0;
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (localClosed_) return;
            localClosed_ = true;
            flags = static_cast<uint16_t>((streamOpened_ ? 0 : kYamuxSyn) | kYamuxFin);
            streamOpened_ = true;
        }
        std::string ignored;
        writeYamuxFrame(kYamuxWindowUpdate, flags, kYamuxStreamId, 0, nullptr, ignored);
    }

private:
    enum class ProcessResult { CONTROL, DATA, EOF_STREAM, ERROR };

    ssize_t copyPending(uint8_t* data, std::size_t length) {
        const std::size_t count = std::min(length, pendingData_.size() - pendingOffset_);
        std::memcpy(data, pendingData_.data() + pendingOffset_, count);
        pendingOffset_ += count;
        if (pendingOffset_ == pendingData_.size()) {
            pendingData_.clear();
            pendingOffset_ = 0;
        }
        return static_cast<ssize_t>(count);
    }

    bool writeYamuxFrame(
        uint8_t type,
        uint16_t flags,
        uint32_t streamId,
        uint32_t length,
        const uint8_t* payload,
        std::string& error
    ) {
        std::lock_guard<std::mutex> lock(writeMutex_);
        std::array<uint8_t, 12> header{};
        header[0] = 0;
        header[1] = type;
        header[2] = static_cast<uint8_t>((flags >> 8U) & 0xffU);
        header[3] = static_cast<uint8_t>(flags & 0xffU);
        for (int index = 0; index < 4; ++index) {
            header[4 + index] = static_cast<uint8_t>((streamId >> ((3 - index) * 8)) & 0xffU);
            header[8 + index] = static_cast<uint8_t>((length >> ((3 - index) * 8)) & 0xffU);
        }
        if (!writeAll(socket_, header.data(), header.size()) ||
            (payload != nullptr && length > 0 && !writeAll(socket_, payload, length))) {
            error = socketError("write yamux frame");
            return false;
        }
        return true;
    }

    ProcessResult processYamuxFrame(std::string& error) {
        std::array<uint8_t, 12> header{};
        if (!readAll(socket_, header.data(), header.size())) {
            error = "yamux connection closed";
            fail(error);
            return ProcessResult::ERROR;
        }
        if (header[0] != 0) {
            error = "unsupported yamux protocol version";
            fail(error);
            return ProcessResult::ERROR;
        }
        const uint8_t type = header[1];
        const uint16_t flags = static_cast<uint16_t>((header[2] << 8U) | header[3]);
        uint32_t streamId = 0;
        uint32_t frameLength = 0;
        for (int index = 0; index < 4; ++index) {
            streamId = (streamId << 8U) | header[4 + index];
            frameLength = (frameLength << 8U) | header[8 + index];
        }

        if (type == kYamuxPing) {
            if ((flags & kYamuxSyn) != 0 &&
                !writeYamuxFrame(kYamuxPing, kYamuxAck, 0, frameLength, nullptr, error)) {
                fail(error);
                return ProcessResult::ERROR;
            }
            return ProcessResult::CONTROL;
        }
        if (type == kYamuxGoAway) {
            error = "yamux server sent go-away code " + std::to_string(frameLength);
            fail(error);
            return ProcessResult::ERROR;
        }
        if (streamId != kYamuxStreamId || (type != kYamuxData && type != kYamuxWindowUpdate)) {
            error = "unexpected yamux frame";
            fail(error);
            return ProcessResult::ERROR;
        }
        if ((flags & kYamuxRst) != 0) {
            error = "yamux stream reset by server";
            fail(error);
            return ProcessResult::ERROR;
        }
        if (type == kYamuxWindowUpdate) {
            bool closed = false;
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                if (UINT32_MAX - sendWindow_ < frameLength) {
                    error = "yamux send window overflow";
                } else {
                    sendWindow_ += frameLength;
                    if ((flags & kYamuxFin) != 0) remoteClosed_ = true;
                    closed = remoteClosed_;
                }
            }
            stateChanged_.notify_all();
            if (!error.empty()) {
                fail(error);
                return ProcessResult::ERROR;
            }
            return closed ? ProcessResult::EOF_STREAM : ProcessResult::CONTROL;
        }

        if (frameLength > kYamuxInitialWindow) {
            error = "yamux data frame exceeds receive window";
            fail(error);
            return ProcessResult::ERROR;
        }
        const std::size_t oldSize = pendingData_.size();
        pendingData_.resize(oldSize + frameLength);
        if (frameLength > 0 && !readAll(socket_, pendingData_.data() + oldSize, frameLength)) {
            error = "yamux data frame truncated";
            fail(error);
            return ProcessResult::ERROR;
        }
        if (frameLength > 0 &&
            !writeYamuxFrame(kYamuxWindowUpdate, 0, kYamuxStreamId, frameLength, nullptr, error)) {
            fail(error);
            return ProcessResult::ERROR;
        }
        bool closed = false;
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if ((flags & kYamuxFin) != 0) remoteClosed_ = true;
            closed = remoteClosed_;
        }
        stateChanged_.notify_all();
        if (frameLength > 0) return ProcessResult::DATA;
        return closed ? ProcessResult::EOF_STREAM : ProcessResult::CONTROL;
    }

    void fail(const std::string& error) {
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            if (terminalError_.empty()) terminalError_ = error;
        }
        stateChanged_.notify_all();
    }

    int socket_;
    bool yamux_;
    std::mutex writeMutex_;
    std::mutex stateMutex_;
    std::condition_variable stateChanged_;
    bool streamOpened_ = false;
    bool localClosed_ = false;
    bool remoteClosed_ = false;
    std::string terminalError_;
    uint32_t sendWindow_ = kYamuxInitialWindow;
    std::vector<uint8_t> pendingData_;
    std::size_t pendingOffset_ = 0;
};

bool readStreamAll(
    aidevmob::frpc::ByteStream& connection,
    uint8_t* data,
    std::size_t length,
    std::string& error
) {
    std::size_t readCount = 0;
    while (readCount < length) {
        const ssize_t count = connection.readBytes(data + readCount, length - readCount, error);
        if (count > 0) {
            readCount += static_cast<std::size_t>(count);
            continue;
        }
        if (count == -2) continue;
        if (count == 0) error = "frp stream closed before the response completed";
        return false;
    }
    return true;
}

bool writeFrpMessage(
    aidevmob::frpc::ByteStream& stream,
    uint8_t type,
    const std::string& json,
    std::string& error
) {
    std::vector<uint8_t> frame(9 + json.size());
    frame[0] = type;
    const uint64_t length = json.size();
    for (int index = 0; index < 8; ++index) {
        frame[1 + index] = static_cast<uint8_t>((length >> ((7 - index) * 8)) & 0xffU);
    }
    std::memcpy(frame.data() + 9, json.data(), json.size());
    return stream.writeBytes(frame.data(), frame.size(), error);
}

bool readFrpMessage(
    aidevmob::frpc::ByteStream& stream,
    uint8_t& type,
    std::string& json,
    std::string& error
) {
    std::array<uint8_t, 9> header{};
    if (!readStreamAll(stream, header.data(), header.size(), error)) return false;
    type = header[0];
    uint64_t length = 0;
    for (int index = 0; index < 8; ++index) length = (length << 8U) | header[1 + index];
    if (length > kMaxMessageSize) {
        error = "frp response exceeds protocol limit";
        return false;
    }
    json.assign(static_cast<std::size_t>(length), '\0');
    return readStreamAll(
        stream,
        reinterpret_cast<uint8_t*>(json.data()),
        json.size(),
        error
    );
}

class FrpcCore {
public:
    FrpcCore(const aidevmob_frpc_stcp_config& config, const aidevmob_frpc_callbacks& callbacks)
        : serverHost_(config.server_host == nullptr ? "" : config.server_host),
          serverPort_(config.server_port),
          serverName_(config.server_name == nullptr ? "" : config.server_name),
          secretKey_(config.secret_key == nullptr ? "" : config.secret_key),
          bindHost_(config.bind_host == nullptr ? "127.0.0.1" : config.bind_host),
          bindPort_(config.bind_port),
          connectTimeoutMs_(config.connect_timeout_ms > 0 ? config.connect_timeout_ms : 10000),
          authToken_(config.auth_token == nullptr ? "" : config.auth_token),
          user_(config.user == nullptr ? "" : config.user),
          serverUser_(config.server_user == nullptr ? "" : config.server_user),
          useTls_(config.use_tls != 0),
          tcpMux_(config.tcp_mux != 0),
          useEncryption_(config.use_encryption != 0),
          useCompression_(config.use_compression != 0),
          callbacks_(callbacks) {}

    ~FrpcCore() {
        stop();
    }

    bool start() {
        bool expected = false;
        if (!started_.compare_exchange_strong(expected, true)) return false;
        stopping_.store(false);
        state(AIDEVMOB_FRPC_STATE_STARTING, nullptr);
        acceptThread_ = std::thread([this] { acceptLoop(); });
        return true;
    }

    void stop() {
        if (!started_.load()) return;
        if (stopping_.exchange(true)) return;

        int listener = -1;
        {
            std::lock_guard<std::mutex> lock(listenerMutex_);
            listener = listener_;
            listener_ = -1;
        }
        closeSocket(listener);

        {
            std::lock_guard<std::mutex> lock(activeMutex_);
            for (const int socket : activeSockets_) {
                shutdown(socket, SHUT_RDWR);
            }
        }

        if (acceptThread_.joinable()) acceptThread_.join();
        for (std::thread& worker : workers_) {
            if (worker.joinable()) worker.join();
        }
        workers_.clear();
        started_.store(false);
        state(AIDEVMOB_FRPC_STATE_STOPPED, nullptr);
    }

private:
    void state(aidevmob_frpc_state value, const char* detail) const {
        if (callbacks_.on_state != nullptr) callbacks_.on_state(callbacks_.context, value, detail);
    }

    void log(const std::string& line) const {
        if (callbacks_.on_log != nullptr) callbacks_.on_log(callbacks_.context, line.c_str());
    }

    int createListener(std::string& error) {
        addrinfo hints{};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        hints.ai_flags = AI_PASSIVE;
        addrinfo* addresses = nullptr;
        const std::string port = std::to_string(bindPort_);
        const int result = getaddrinfo(bindHost_.c_str(), port.c_str(), &hints, &addresses);
        if (result != 0) {
            error = "resolve bind address: " + std::string(gai_strerror(result));
            return -1;
        }

        int listener = -1;
        for (addrinfo* address = addresses; address != nullptr; address = address->ai_next) {
            listener = socket(address->ai_family, address->ai_socktype, address->ai_protocol);
            if (listener < 0) continue;
            const int enabled = 1;
            setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled));
            if (bind(listener, address->ai_addr, address->ai_addrlen) == 0 && listen(listener, 16) == 0) {
                break;
            }
            close(listener);
            listener = -1;
        }
        freeaddrinfo(addresses);
        if (listener < 0) error = socketError("listen on " + bindHost_ + ":" + port);
        return listener;
    }

    void acceptLoop() {
        std::string error;
        const int controlSocket = connectServer(error);
        if (controlSocket < 0) {
            state(AIDEVMOB_FRPC_STATE_ERROR, error.c_str());
            return;
        }
        {
            std::lock_guard<std::mutex> lock(activeMutex_);
            activeSockets_.insert(controlSocket);
        }
        FrpConnection controlConnection(controlSocket, tcpMux_);
        if (!login(controlConnection, error)) {
            finishSocket(controlSocket);
            state(AIDEVMOB_FRPC_STATE_ERROR, error.c_str());
            return;
        }
        aidevmob::frpc::CryptoStream encryptedControl(controlConnection, authToken_);
        std::thread heartbeatThread([this, &encryptedControl] { heartbeatLoop(encryptedControl); });

        const int listener = createListener(error);
        if (listener < 0) {
            finishSocket(controlSocket);
            if (heartbeatThread.joinable()) heartbeatThread.join();
            state(AIDEVMOB_FRPC_STATE_ERROR, error.c_str());
            return;
        }
        if (stopping_.load()) {
            closeSocket(listener);
            finishSocket(controlSocket);
            if (heartbeatThread.joinable()) heartbeatThread.join();
            return;
        }
        {
            std::lock_guard<std::mutex> lock(listenerMutex_);
            listener_ = listener;
        }
        log("C++ core listening on " + bindHost_ + ":" + std::to_string(bindPort_));
        state(AIDEVMOB_FRPC_STATE_RUNNING, nullptr);

        while (!stopping_.load()) {
            sockaddr_storage peer{};
            socklen_t peerLength = sizeof(peer);
            const int localSocket = accept(listener, reinterpret_cast<sockaddr*>(&peer), &peerLength);
            if (localSocket < 0) {
                if (stopping_.load()) break;
                if (errno == EINTR) continue;
                error = socketError("accept local connection");
                state(AIDEVMOB_FRPC_STATE_ERROR, error.c_str());
                break;
            }
            {
                std::lock_guard<std::mutex> lock(activeMutex_);
                activeSockets_.insert(localSocket);
            }
            workers_.emplace_back([this, localSocket] { handleLocalConnection(localSocket); });
        }
        if (!stopping_.load()) {
            std::lock_guard<std::mutex> lock(listenerMutex_);
            if (listener_ == listener) {
                listener_ = -1;
                closeSocket(listener);
            }
        }
        finishSocket(controlSocket);
        if (heartbeatThread.joinable()) heartbeatThread.join();
    }

    bool login(FrpConnection& connection, std::string& error) {
        const int64_t timestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        const std::string json = "{\"version\":\"0.70.1\",\"hostname\":\"aidevmob\"," \
            "\"os\":\"mobile\",\"arch\":\"native\",\"user\":\"" + jsonEscape(user_) +
            "\",\"privilege_key\":\"" + md5Hex(authToken_ + std::to_string(timestamp)) +
            "\",\"timestamp\":" + std::to_string(timestamp) + ",\"pool_count\":0}";
        if (!writeFrpMessage(connection, static_cast<uint8_t>('o'), json, error)) {
            error = "send Login: " + error;
            return false;
        }
        uint8_t type = 0;
        std::string response;
        if (!readFrpMessage(connection, type, response, error)) {
            error = "read LoginResp: " + error;
            return false;
        }
        if (type != static_cast<uint8_t>('1')) {
            error = "unexpected login response type " + std::to_string(type);
            return false;
        }
        std::string serverError;
        if (extractJsonString(response, "error", serverError) && !serverError.empty()) {
            error = serverError;
            return false;
        }
        if (!extractJsonString(response, "run_id", runId_) || runId_.empty()) {
            error = "login response omitted run_id";
            return false;
        }
        log("C++ core login success, run_id=" + runId_);
        return true;
    }

    void heartbeatLoop(aidevmob::frpc::ByteStream& control) {
        while (!stopping_.load()) {
            for (int tick = 0; tick < 300 && !stopping_.load(); ++tick) {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
            if (stopping_.load()) break;
            const int64_t timestamp = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::system_clock::now().time_since_epoch()
            ).count();
            const std::string json = "{\"privilege_key\":\"" +
                md5Hex(authToken_ + std::to_string(timestamp)) + "\",\"timestamp\":" +
                std::to_string(timestamp) + "}";
            std::string error;
            if (!writeFrpMessage(control, static_cast<uint8_t>('h'), json, error)) {
                if (!stopping_.load()) controlFailure("control heartbeat write failed: " + error);
                break;
            }
            uint8_t type = 0;
            std::string response;
            if (!readFrpMessage(control, type, response, error) || type != static_cast<uint8_t>('4')) {
                if (!stopping_.load()) controlFailure("control heartbeat read failed: " + error);
                break;
            }
            std::string serverError;
            if (extractJsonString(response, "error", serverError) && !serverError.empty()) {
                controlFailure("control heartbeat rejected: " + serverError);
                break;
            }
        }
    }

    void controlFailure(const std::string& error) {
        if (controlFailed_.exchange(true)) return;
        log(error);
        state(AIDEVMOB_FRPC_STATE_ERROR, error.c_str());
        int listener = -1;
        {
            std::lock_guard<std::mutex> lock(listenerMutex_);
            listener = listener_;
            listener_ = -1;
        }
        closeSocket(listener);
    }

    int connectServer(std::string& error) const {
        if (useTls_) {
            if (callbacks_.open_transport == nullptr) {
                error = "TLS transport requires a platform open_transport callback";
                return -1;
            }
            const int socket = callbacks_.open_transport(
                callbacks_.context,
                serverHost_.c_str(),
                serverPort_,
                1,
                connectTimeoutMs_
            );
            if (socket < 0) error = "platform TLS transport connection failed";
            return socket;
        }

        addrinfo hints{};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        addrinfo* addresses = nullptr;
        const std::string port = std::to_string(serverPort_);
        const int result = getaddrinfo(serverHost_.c_str(), port.c_str(), &hints, &addresses);
        if (result != 0) {
            error = "resolve server address: " + std::string(gai_strerror(result));
            return -1;
        }

        int connected = -1;
        for (addrinfo* address = addresses; address != nullptr && !stopping_.load(); address = address->ai_next) {
            const int candidate = socket(address->ai_family, address->ai_socktype, address->ai_protocol);
            if (candidate < 0) continue;
            const int originalFlags = fcntl(candidate, F_GETFL, 0);
            if (originalFlags < 0) {
                close(candidate);
                continue;
            }
            fcntl(candidate, F_SETFL, originalFlags | O_NONBLOCK);
            int connectResult = connect(candidate, address->ai_addr, address->ai_addrlen);
            if (connectResult < 0 && errno == EINPROGRESS) {
                pollfd descriptor{candidate, POLLOUT, 0};
                int elapsedMs = 0;
                connectResult = -1;
                while (!stopping_.load() && elapsedMs < connectTimeoutMs_) {
                    const int waitMs = std::min(250, connectTimeoutMs_ - elapsedMs);
                    const int pollResult = poll(&descriptor, 1, waitMs);
                    elapsedMs += waitMs;
                    if (pollResult > 0) {
                        int socketResult = 0;
                        socklen_t resultLength = sizeof(socketResult);
                        getsockopt(candidate, SOL_SOCKET, SO_ERROR, &socketResult, &resultLength);
                        connectResult = socketResult == 0 ? 0 : -1;
                        if (socketResult != 0) errno = socketResult;
                        break;
                    }
                    if (pollResult < 0 && errno != EINTR) break;
                }
                if (connectResult != 0 && elapsedMs >= connectTimeoutMs_) {
                    errno = ETIMEDOUT;
                }
            }
            fcntl(candidate, F_SETFL, originalFlags);
            if (connectResult == 0) {
                const int enabled = 1;
                setsockopt(candidate, SOL_SOCKET, SO_KEEPALIVE, &enabled, sizeof(enabled));
#ifdef SO_NOSIGPIPE
                setsockopt(candidate, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled));
#endif
                connected = candidate;
                break;
            }
            close(candidate);
        }
        freeaddrinfo(addresses);
        if (connected < 0) error = socketError("connect to " + serverHost_ + ":" + port);
        return connected;
    }

    bool openVisitorConnection(
        aidevmob::frpc::ByteStream& connection,
        std::string& error,
        bool& receivedFrpResponse
    ) const {
        receivedFrpResponse = false;
        const int64_t timestamp = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count();
        const std::string scopedServerName = !serverUser_.empty()
            ? serverUser_ + "." + serverName_
            : (!user_.empty() ? user_ + "." + serverName_ : serverName_);
        const std::string json = "{\"run_id\":\"" + jsonEscape(runId_) +
            "\",\"proxy_name\":\"" + jsonEscape(scopedServerName) +
            "\",\"sign_key\":\"" + md5Hex(secretKey_ + std::to_string(timestamp)) +
            "\",\"timestamp\":" + std::to_string(timestamp) +
            ",\"use_encryption\":" + (useEncryption_ ? "true" : "false") +
            ",\"use_compression\":" + (useCompression_ ? "true" : "false") + "}";

        if (!writeFrpMessage(connection, static_cast<uint8_t>('v'), json, error)) {
            error = "send NewVisitorConn: " + error;
            return false;
        }

        uint8_t type = 0;
        std::string response;
        if (!readFrpMessage(connection, type, response, error)) {
            error = "read NewVisitorConnResp: " + error;
            return false;
        }
        if (type != static_cast<uint8_t>('3')) {
            error = "unexpected frp response type " + std::to_string(type);
            return false;
        }
        receivedFrpResponse = true;
        std::string serverError;
        if (extractJsonString(response, "error", serverError) && !serverError.empty()) {
            error = serverError;
            return false;
        }
        return true;
    }

    void handleLocalConnection(int localSocket) {
        std::string error;
        int remoteSocket = connectServer(error);
        if (remoteSocket < 0) {
            log(error);
            finishSocket(localSocket);
            return;
        }
        {
            std::lock_guard<std::mutex> lock(activeMutex_);
            activeSockets_.insert(remoteSocket);
        }
        auto connection = std::make_unique<FrpConnection>(remoteSocket, tcpMux_);
        bool receivedFrpResponse = false;
        if (!openVisitorConnection(*connection, error, receivedFrpResponse)) {
            log("STCP visitor handshake failed: " + error);
            finishSocket(remoteSocket);
            finishSocket(localSocket);
            return;
        }

        log("STCP visitor connected to " + serverName_);
        aidevmob::frpc::ByteStream* payloadStream = connection.get();
        std::unique_ptr<aidevmob::frpc::CryptoStream> cryptoStream;
        std::unique_ptr<aidevmob::frpc::SnappyStream> snappyStream;
        if (useEncryption_) {
            cryptoStream = std::make_unique<aidevmob::frpc::CryptoStream>(*payloadStream, secretKey_);
            payloadStream = cryptoStream.get();
        }
        if (useCompression_) {
            snappyStream = std::make_unique<aidevmob::frpc::SnappyStream>(*payloadStream);
            payloadStream = snappyStream.get();
        }
        relay(localSocket, *payloadStream);
        finishSocket(remoteSocket);
        finishSocket(localSocket);
    }

    void relay(int localSocket, aidevmob::frpc::ByteStream& connection) const {
        std::thread remoteReader([this, localSocket, &connection] {
            std::array<uint8_t, 32768> buffer{};
            while (!stopping_.load()) {
                std::string error;
                const ssize_t count = connection.readBytes(buffer.data(), buffer.size(), error);
                if (count <= 0 || !writeAll(localSocket, buffer.data(), static_cast<std::size_t>(count))) {
                    if (count < 0 && !error.empty()) log("STCP remote read failed: " + error);
                    break;
                }
            }
            shutdown(localSocket, SHUT_WR);
            shutdown(localSocket, SHUT_RD);
        });

        std::array<uint8_t, 32768> buffer{};
        while (!stopping_.load()) {
            const ssize_t count = recv(localSocket, buffer.data(), buffer.size(), 0);
            if (count > 0) {
                std::string error;
                if (!connection.writeBytes(buffer.data(), static_cast<std::size_t>(count), error)) {
                    if (!error.empty()) log("STCP remote write failed: " + error);
                    break;
                }
                continue;
            }
            if (count < 0 && errno == EINTR) continue;
            break;
        }
        connection.closeWrite();
        if (remoteReader.joinable()) remoteReader.join();
    }

    void finishSocket(int socket) {
        {
            std::lock_guard<std::mutex> lock(activeMutex_);
            activeSockets_.erase(socket);
        }
        closeSocket(socket);
    }

    std::string serverHost_;
    uint16_t serverPort_;
    std::string serverName_;
    std::string secretKey_;
    std::string bindHost_;
    uint16_t bindPort_;
    int connectTimeoutMs_;
    std::string authToken_;
    std::string user_;
    std::string serverUser_;
    bool useTls_;
    bool tcpMux_;
    bool useEncryption_;
    bool useCompression_;
    std::string runId_;
    aidevmob_frpc_callbacks callbacks_{};
    std::atomic<bool> started_{false};
    std::atomic<bool> stopping_{false};
    std::atomic<bool> controlFailed_{false};
    std::mutex listenerMutex_;
    int listener_ = -1;
    std::thread acceptThread_;
    std::mutex activeMutex_;
    std::set<int> activeSockets_;
    std::vector<std::thread> workers_;
};

}  // namespace

struct aidevmob_frpc_core {
    explicit aidevmob_frpc_core(std::unique_ptr<FrpcCore> value) : implementation(std::move(value)) {}
    std::unique_ptr<FrpcCore> implementation;
};

extern "C" aidevmob_frpc_core* aidevmob_frpc_core_create(
    const aidevmob_frpc_stcp_config* config,
    const aidevmob_frpc_callbacks* callbacks
) {
    if (config == nullptr || callbacks == nullptr || config->server_host == nullptr ||
        config->server_name == nullptr || config->secret_key == nullptr || config->server_port == 0 ||
        config->bind_port == 0) {
        return nullptr;
    }
    try {
        return new aidevmob_frpc_core(std::make_unique<FrpcCore>(*config, *callbacks));
    } catch (...) {
        return nullptr;
    }
}

extern "C" int aidevmob_frpc_core_start(aidevmob_frpc_core* core) {
    return core != nullptr && core->implementation->start() ? 0 : -1;
}

extern "C" void aidevmob_frpc_core_stop(aidevmob_frpc_core* core) {
    if (core != nullptr) core->implementation->stop();
}

extern "C" void aidevmob_frpc_core_destroy(aidevmob_frpc_core* core) {
    delete core;
}
