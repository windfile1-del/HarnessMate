// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <openssl/aead.h>
#include <openssl/curve25519.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
using Socket = SOCKET;
constexpr Socket invalidSocket = INVALID_SOCKET;
#else
using Socket = int;
constexpr Socket invalidSocket = -1;
#endif
namespace vega::adb {
using Bytes = std::vector<uint8_t>;
void require(bool ok, const char* message);
struct Secret : Bytes {
    using Bytes::Bytes;
    ~Secret();
};
struct Identity {
    bssl::UniquePtr<EVP_PKEY> key;
    bssl::UniquePtr<X509> cert;
    explicit Identity(const Bytes& pem);
};
Bytes generateIdentity();
Bytes hostPeerInfo(EVP_PKEY* key);
Bytes signAuthToken(EVP_PKEY* key, const Bytes& token);
Bytes authPublicKey(EVP_PKEY* key);
std::string deviceGuid(const Bytes& peer);
uint32_t packetLength(const std::array<uint8_t,6>& header, uint8_t expected);
std::array<uint8_t,6> packetHeader(uint8_t type, size_t size);
class PairingAuth {
    bssl::UniquePtr<SPAKE2_CTX> spake_;
    bssl::UniquePtr<EVP_AEAD_CTX> aead_;
    uint64_t enc_ = 0, dec_ = 0;
public:
    Bytes message;
    PairingAuth(bool client, const Bytes& password);
    void finish(const Bytes& message);
    Bytes encrypt(const Bytes& plain);
    Bytes decrypt(const Bytes& cipher);
};
struct Deadline {
    std::chrono::steady_clock::time_point end;
    explicit Deadline(int timeoutMs);
    int remaining() const;
};
void initSockets();
void closeSocket(Socket fd);
struct Connection {
    Socket fd = invalidSocket;
    std::atomic<bool> cancelled{false};
    std::timed_mutex reader, writer;
    std::mutex sslMutex;
    Identity identity;
    bssl::UniquePtr<SSL_CTX> context;
    bssl::UniquePtr<SSL> ssl;
    explicit Connection(const Bytes& pem): identity(pem) {}
    ~Connection();
    void cancel();
    void wait(short events, const Deadline& deadline);
    void connectLoopback(int port, const Deadline& deadline, uint64_t network=0);
    // Server mode is for the host protocol fixture only.
    void upgrade(const Deadline& deadline, bool server=false);
    int read(uint8_t* data, size_t length, const Deadline& deadline);
    void write(const uint8_t* data, size_t length, const Deadline& deadline);
    Bytes readFully(size_t length, const Deadline& deadline);
    Bytes exporter();
};
std::shared_ptr<Connection> connect(int port, const Bytes& pem, const Deadline& deadline, uint64_t network=0);
std::string pair(int port,const std::string& code,const Bytes& pem,int timeoutMs,uint64_t network=0);
uint64_t registerConnection(std::shared_ptr<Connection> connection);
std::shared_ptr<Connection> lookup(uint64_t handle);
void closeHandle(uint64_t handle);
}
