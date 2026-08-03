#include "aidevmob/frpc_core.h"

#include <csignal>
#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>

#ifdef AIDEVMOB_FRPC_DRIVER_OPENSSL
#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <openssl/ssl.h>
#endif

namespace {

volatile std::sig_atomic_t stopped = 0;

void handleSignal(int) {
    stopped = 1;
}

void onState(void*, aidevmob_frpc_state state, const char* detail) {
    std::cerr << "state=" << static_cast<int>(state);
    if (detail != nullptr) std::cerr << " detail=" << detail;
    std::cerr << '\n';
}

void onLog(void*, const char* line) {
    std::cerr << (line == nullptr ? "" : line) << '\n';
}

#ifdef AIDEVMOB_FRPC_DRIVER_OPENSSL
int connectTcp(const char* host, uint16_t port) {
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* addresses = nullptr;
    if (getaddrinfo(host, std::to_string(port).c_str(), &hints, &addresses) != 0) return -1;
    int connected = -1;
    for (addrinfo* address = addresses; address != nullptr; address = address->ai_next) {
        const int socketFd = socket(address->ai_family, address->ai_socktype, address->ai_protocol);
        if (socketFd < 0) continue;
        if (connect(socketFd, address->ai_addr, address->ai_addrlen) == 0) {
            connected = socketFd;
            break;
        }
        close(socketFd);
    }
    freeaddrinfo(addresses);
    return connected;
}

bool sslWriteAll(SSL* ssl, const uint8_t* data, std::size_t length) {
    std::size_t offset = 0;
    while (offset < length) {
        const int count = SSL_write(ssl, data + offset, static_cast<int>(length - offset));
        if (count <= 0) {
            const int error = SSL_get_error(ssl, count);
            if (error == SSL_ERROR_WANT_READ || error == SSL_ERROR_WANT_WRITE) continue;
            return false;
        }
        offset += static_cast<std::size_t>(count);
    }
    return true;
}

void runTlsPump(int bridgeFd, int networkFd, SSL_CTX* context, SSL* ssl) {
    const int flags = fcntl(networkFd, F_GETFL, 0);
    if (flags >= 0) fcntl(networkFd, F_SETFL, flags | O_NONBLOCK);
    std::array<uint8_t, 32768> buffer{};
    while (true) {
        std::array<pollfd, 2> descriptors{{
            {bridgeFd, POLLIN, 0},
            {networkFd, POLLIN, 0},
        }};
        const int ready = poll(descriptors.data(), descriptors.size(), SSL_pending(ssl) > 0 ? 0 : 1000);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if ((descriptors[0].revents & (POLLIN | POLLHUP | POLLERR)) != 0) {
            const ssize_t count = recv(bridgeFd, buffer.data(), buffer.size(), 0);
            if (count <= 0 || !sslWriteAll(ssl, buffer.data(), static_cast<std::size_t>(count))) break;
        }
        if (SSL_pending(ssl) > 0 || (descriptors[1].revents & (POLLIN | POLLHUP | POLLERR)) != 0) {
            const int count = SSL_read(ssl, buffer.data(), static_cast<int>(buffer.size()));
            if (count <= 0) {
                const int error = SSL_get_error(ssl, count);
                if (error == SSL_ERROR_WANT_READ || error == SSL_ERROR_WANT_WRITE) continue;
                break;
            }
            if (send(bridgeFd, buffer.data(), static_cast<std::size_t>(count), 0) != count) break;
        }
    }
    shutdown(bridgeFd, SHUT_RDWR);
    close(bridgeFd);
    SSL_shutdown(ssl);
    SSL_free(ssl);
    SSL_CTX_free(context);
    close(networkFd);
}

int openTransport(void*, const char* host, uint16_t port, int useTls, int) {
    if (useTls == 0) return -1;
    const int networkFd = connectTcp(host, port);
    if (networkFd < 0) return -1;
    SSL_CTX* context = SSL_CTX_new(TLS_client_method());
    SSL_CTX_set_verify(context, SSL_VERIFY_NONE, nullptr);
    SSL* ssl = SSL_new(context);
    SSL_set_tlsext_host_name(ssl, host);
    SSL_set_fd(ssl, networkFd);
    if (SSL_connect(ssl) != 1) {
        SSL_free(ssl);
        SSL_CTX_free(context);
        close(networkFd);
        return -1;
    }
    std::cerr << "TLS handshake complete for " << host << ':' << port << '\n';
    std::array<int, 2> pair{};
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair.data()) != 0) {
        SSL_free(ssl);
        SSL_CTX_free(context);
        close(networkFd);
        return -1;
    }
    std::thread(runTlsPump, pair[1], networkFd, context, ssl).detach();
    return pair[0];
}
#endif

}  // namespace

int main(int argc, char** argv) {
    if (argc != 7 && argc != 13 && argc != 14) {
        std::cerr << "usage: " << argv[0]
                  << " <server-host> <server-port> <server-name> <secret-key> <bind-host> <bind-port>"
                  << " [auth-token user server-user tcp-mux encryption compression [tls]]\n";
        return 2;
    }

    const bool extended = argc >= 13;
    const char* authToken = extended ? argv[7] : "";
    const char* user = extended ? argv[8] : "";
    const char* serverUser = extended ? argv[9] : "";

    aidevmob_frpc_stcp_config config{
        argv[1],
        static_cast<uint16_t>(std::stoi(argv[2])),
        argv[3],
        argv[4],
        argv[5],
        static_cast<uint16_t>(std::stoi(argv[6])),
        10000,
        authToken,
        user,
        serverUser,
        argc == 14 ? std::stoi(argv[13]) : 0,
        extended ? std::stoi(argv[10]) : 1,
        extended ? std::stoi(argv[11]) : 0,
        extended ? std::stoi(argv[12]) : 0,
    };
    aidevmob_frpc_callbacks callbacks{
        nullptr,
        onState,
        onLog,
#ifdef AIDEVMOB_FRPC_DRIVER_OPENSSL
        openTransport,
#else
        nullptr,
#endif
    };
    aidevmob_frpc_core* core = aidevmob_frpc_core_create(&config, &callbacks);
    if (core == nullptr || aidevmob_frpc_core_start(core) != 0) {
        std::cerr << "failed to create core\n";
        aidevmob_frpc_core_destroy(core);
        return 1;
    }

    std::signal(SIGINT, handleSignal);
    std::signal(SIGTERM, handleSignal);
    while (!stopped) std::this_thread::sleep_for(std::chrono::milliseconds(100));

    aidevmob_frpc_core_stop(core);
    aidevmob_frpc_core_destroy(core);
    return 0;
}
