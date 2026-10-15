// SPDX-License-Identifier: Apache-2.0
#include "adb.h"
#include <algorithm>
#include <climits>
#include <cstring>
#include <unordered_map>
#include <openssl/err.h>
#ifdef __ANDROID__
#include <android/multinetwork.h>
#endif
#ifndef _WIN32
#include <arpa/inet.h>
#include <cerrno>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#endif
namespace vega::adb {
#ifdef _WIN32
static int socketError(){return WSAGetLastError();}
static bool wouldBlock(int n){return n==WSAEWOULDBLOCK || n==WSAEINPROGRESS;}
static bool interrupted(int n){return n==WSAEINTR;}
void initSockets(){static const int status=[](){WSADATA d;return WSAStartup(MAKEWORD(2,2),&d);}();require(status==0,"ADB_SOCKET_INIT_FAILED");}
void closeSocket(Socket fd){if(fd!=invalidSocket)closesocket(fd);}
static void nonblocking(Socket fd){u_long mode=1;require(ioctlsocket(fd,FIONBIO,&mode)==0,"ADB_SOCKET_NONBLOCK_FAILED");}
#else
static int socketError(){return errno;}
static bool wouldBlock(int n){return n==EAGAIN || n==EWOULDBLOCK || n==EINPROGRESS;}
static bool interrupted(int n){return n==EINTR;}
void initSockets(){}
void closeSocket(Socket fd){if(fd!=invalidSocket)::close(fd);}
static void nonblocking(Socket fd){
    int flags=fcntl(fd,F_GETFL);
    require(flags>=0 && fcntl(fd,F_SETFL,flags|O_NONBLOCK)==0 && fcntl(fd,F_SETFD,FD_CLOEXEC)==0,
            "ADB_SOCKET_NONBLOCK_FAILED");
}
#endif
Deadline::Deadline(int ms){require(ms>0,"ADB_TIMEOUT_INVALID");end=std::chrono::steady_clock::now()+std::chrono::milliseconds(ms);}
int Deadline::remaining()const{
    auto now=std::chrono::steady_clock::now();
    require(now<end,"ADB_TIMEOUT");
    auto n=std::chrono::duration_cast<std::chrono::milliseconds>(end-now).count();
    return static_cast<int>(std::min<int64_t>(INT_MAX,std::max<int64_t>(1,n)));
}
Connection::~Connection(){ssl.reset();context.reset();closeSocket(fd);}
void Connection::cancel(){
    if(!cancelled.exchange(true) && fd!=invalidSocket) {
#ifdef _WIN32
        shutdown(fd,SD_BOTH);
#else
        shutdown(fd,SHUT_RDWR);
#endif
    }
}
void Connection::wait(short events,const Deadline& d){
    while(true){
        require(!cancelled.load(),"ADB_CLOSED");
        int ms=std::min(d.remaining(),25);
#ifdef _WIN32
        WSAPOLLFD p{fd,events,0};int n=WSAPoll(&p,1,ms);
#else
        pollfd p{fd,events,0};int n=poll(&p,1,ms);
#endif
        require(!cancelled.load(),"ADB_CLOSED");
        if(n>0)return; // next syscall distinguishes EOF and connection error
        if(n<0 && !interrupted(socketError()))throw std::runtime_error("ADB_POLL_FAILED");
    }
}
static std::unique_lock<std::timed_mutex> acquire(std::timed_mutex& mutex,Connection& c,const Deadline& d){
    std::unique_lock<std::timed_mutex> lock(mutex,std::defer_lock);
    while(!lock.try_lock_for(std::chrono::milliseconds(std::min(d.remaining(),25))))
        require(!c.cancelled.load(),"ADB_CLOSED");
    require(!c.cancelled.load(),"ADB_CLOSED");d.remaining();return lock;
}
// Own socket BIO avoids SIGPIPE without changing the application's signal handlers.
static const BIO_METHOD* socketBio(){
    static const auto method=[](){
        bssl::UniquePtr<BIO_METHOD> m(BIO_meth_new(BIO_TYPE_SOCKET,"vega loopback"));
        require(bool(m),"ADB_BIO_FAILED");
        BIO_meth_set_create(m.get(),[](BIO* b){BIO_set_init(b,1);return 1;});
        BIO_meth_set_destroy(m.get(),[](BIO*){return 1;});
        BIO_meth_set_ctrl(m.get(),[](BIO*,int cmd,long,void*)->long{return cmd==BIO_CTRL_FLUSH?1:0;});
        BIO_meth_set_read(m.get(),[](BIO* b,char* out,int len){
            BIO_clear_retry_flags(b);auto* c=static_cast<Connection*>(BIO_get_data(b));
            int n=static_cast<int>(recv(c->fd,out,len,0));
            if(n<0 && (wouldBlock(socketError()) || interrupted(socketError())))BIO_set_retry_read(b);
            return n;
        });
        BIO_meth_set_write(m.get(),[](BIO* b,const char* in,int len){
            BIO_clear_retry_flags(b);auto* c=static_cast<Connection*>(BIO_get_data(b));
#ifdef _WIN32
            int flags=0;
#else
            int flags=MSG_NOSIGNAL;
#endif
            int n=static_cast<int>(send(c->fd,in,len,flags));
            if(n<0 && (wouldBlock(socketError()) || interrupted(socketError())))BIO_set_retry_write(b);
            return n;
        });
        return m;
    }();
    return method.get();
}
void Connection::connectLoopback(int port,const Deadline& d,uint64_t network){
    require(port>=1 && port<=65535,"ADB_PORT_INVALID");initSockets();
    fd=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
    require(fd!=invalidSocket,"ADB_SOCKET_FAILED");nonblocking(fd);
    if(network){
#ifdef __ANDROID__
        // Bind only this unconnected socket, not the App's process/default network.
        // The destination below remains loopback even when the VPN IP changes.
        if(android_setsocknetwork(static_cast<net_handle_t>(network),fd)!=0)
            throw std::runtime_error(errno==EPERM || errno==EACCES ?
                "ADB_VPN_BYPASS_BLOCKED" : "ADB_LOCAL_NETWORK_BIND_FAILED");
#else
        throw std::runtime_error("ADB_NETWORK_BIND_UNSUPPORTED");
#endif
    }
    sockaddr_in addr{};addr.sin_family=AF_INET;addr.sin_port=htons(uint16_t(port));
    addr.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    d.remaining();
    if(::connect(fd,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))!=0){
        require(wouldBlock(socketError()),"ADB_CONNECT_FAILED");wait(POLLOUT,d);
        int err=0;
#ifdef _WIN32
        int len=sizeof(err);
#else
        socklen_t len=sizeof(err);
#endif
        require(getsockopt(fd,SOL_SOCKET,SO_ERROR,reinterpret_cast<char*>(&err),&len)==0 && err==0,
                "ADB_CONNECT_FAILED");
    }
}
void Connection::upgrade(const Deadline& d,bool server){
    auto r=acquire(reader,*this,d);auto w=acquire(writer,*this,d);
    try{
        require(!ssl,"ADB_TLS_ALREADY_ACTIVE");
        // Test fixtures adopt accepted sockets; all production sockets originate in connectLoopback.
        nonblocking(fd);
        context.reset(SSL_CTX_new(TLS_method()));
        require(context && SSL_CTX_set_min_proto_version(context.get(),TLS1_3_VERSION)==1 &&
                SSL_CTX_set_max_proto_version(context.get(),TLS1_3_VERSION)==1 &&
                SSL_CTX_use_certificate(context.get(),identity.cert.get())==1 &&
                SSL_CTX_use_PrivateKey(context.get(),identity.key.get())==1 &&
                SSL_CTX_check_private_key(context.get())==1,"ADB_TLS_SETUP_FAILED");
        // AOSP ADB uses self-signed certificates. Pairing authenticates the TLS channel with PAKE.
        SSL_CTX_set_cert_verify_callback(context.get(),[](X509_STORE_CTX*,void*){return 1;},nullptr);
        SSL_CTX_set_verify(context.get(),SSL_VERIFY_PEER|SSL_VERIFY_FAIL_IF_NO_PEER_CERT,nullptr);
        ssl.reset(SSL_new(context.get()));
        bssl::UniquePtr<BIO> bio(BIO_new(socketBio()));
        require(ssl && bio,"ADB_TLS_SETUP_FAILED");BIO_set_data(bio.get(),this);
        BIO* shared=bio.release();SSL_set_bio(ssl.get(),shared,shared);
        if(server)SSL_set_accept_state(ssl.get());else SSL_set_connect_state(ssl.get());
        while(true){
            require(!cancelled.load(),"ADB_CLOSED");d.remaining();
            ERR_clear_error();int n=SSL_do_handshake(ssl.get());
            if(n==1)break;
            int err=SSL_get_error(ssl.get(),n);
            require(err==SSL_ERROR_WANT_READ || err==SSL_ERROR_WANT_WRITE,"ADB_TLS_HANDSHAKE_FAILED");
            wait(err==SSL_ERROR_WANT_READ?POLLIN:POLLOUT,d);
        }
    }catch(...){cancel();throw;}
}
int Connection::read(uint8_t* data,size_t length,const Deadline& d){
    require(length<=INT_MAX,"ADB_LENGTH_INVALID");
    auto guard=acquire(reader,*this,d);
    if(!length)return 0;
    try{
        while(true){
            require(!cancelled.load(),"ADB_CLOSED");d.remaining();
            int n,err=0;bool tls;
            {
                std::lock_guard<std::mutex> lock(sslMutex);tls=bool(ssl);
                if(tls){ERR_clear_error();n=SSL_read(ssl.get(),data,static_cast<int>(length));if(n<=0)err=SSL_get_error(ssl.get(),n);}
                else {n=static_cast<int>(recv(fd,reinterpret_cast<char*>(data),static_cast<int>(length),0));if(n<0)err=socketError();}
            }
            require(!cancelled.load(),"ADB_CLOSED");
            if(n>0)return n;
            if((tls && err==SSL_ERROR_ZERO_RETURN) || (!tls && n==0))return -1;
            if(tls){require(err==SSL_ERROR_WANT_READ || err==SSL_ERROR_WANT_WRITE,"ADB_TLS_READ_FAILED");wait(err==SSL_ERROR_WANT_READ?POLLIN:POLLOUT,d);}
            else if(n<0 && interrupted(err))continue;
            else{require(wouldBlock(err),"ADB_READ_FAILED");wait(POLLIN,d);}
        }
    }catch(...){cancel();throw;}
}
void Connection::write(const uint8_t* data,size_t length,const Deadline& d){
    require(length<=INT_MAX,"ADB_LENGTH_INVALID");auto guard=acquire(writer,*this,d);
    size_t done=0;
    try{
        while(done<length){
            require(!cancelled.load(),"ADB_CLOSED");d.remaining();int n,err=0;bool tls;
            {
                std::lock_guard<std::mutex> lock(sslMutex);tls=bool(ssl);
                if(tls){ERR_clear_error();n=SSL_write(ssl.get(),data+done,static_cast<int>(length-done));if(n<=0)err=SSL_get_error(ssl.get(),n);}
                else{
#ifdef _WIN32
                    int flags=0;
#else
                    int flags=MSG_NOSIGNAL;
#endif
                    n=static_cast<int>(send(fd,reinterpret_cast<const char*>(data+done),static_cast<int>(length-done),flags));if(n<0)err=socketError();
                }
            }
            require(!cancelled.load(),"ADB_CLOSED");
            if(n>0){done+=n;continue;}
            if(tls){require(err==SSL_ERROR_WANT_READ || err==SSL_ERROR_WANT_WRITE,"ADB_TLS_WRITE_FAILED");wait(err==SSL_ERROR_WANT_READ?POLLIN:POLLOUT,d);}
            else if(n<0 && interrupted(err))continue;
            else{require(n<0 && wouldBlock(err),"ADB_WRITE_FAILED");wait(POLLOUT,d);}
        }
    }catch(...){cancel();throw;}
}
Bytes Connection::readFully(size_t n,const Deadline& d){
    require(n>0 && n<=16384,"ADB_LENGTH_INVALID");Bytes out(n);size_t done=0;
    while(done<n){int got=read(out.data()+done,n-done,d);require(got>0,"ADB_UNEXPECTED_EOF");done+=got;}
    return out;
}
Bytes Connection::exporter(){
    static const char label[]="adb-label";Bytes out(64);
    std::lock_guard<std::mutex> lock(sslMutex);
    require(ssl && SSL_export_keying_material(ssl.get(),out.data(),out.size(),label,sizeof(label),nullptr,0,0)==1,
            "ADB_TLS_EXPORTER_FAILED");return out;
}
std::shared_ptr<Connection> connect(int port,const Bytes& pem,const Deadline& d,uint64_t network){
#ifdef __ANDROID__
    if(network){
        // A VPN need not intercept loopback. Preserve the working default path;
        // retry on a physical network only before any ADB/TLS bytes are sent.
        // Both attempts share the caller's overall deadline.
        try{
            auto local=connect(port,pem,Deadline(std::min(3000,d.remaining())),0);
            d.remaining();return local;
        }catch(const std::exception& e){
            if(std::string(e.what())!="ADB_CONNECT_FAILED" && std::string(e.what())!="ADB_TIMEOUT")throw;
            d.remaining();
        }
    }
#endif
    d.remaining();auto c=std::make_shared<Connection>(pem);c->connectLoopback(port,d,network);d.remaining();return c;
}
static Bytes receivePacket(Connection& c,uint8_t type,const Deadline& d){
    auto raw=c.readFully(6,d);std::array<uint8_t,6> h{};std::copy(raw.begin(),raw.end(),h.begin());
    return c.readFully(packetLength(h,type),d);
}
static void sendPacket(Connection& c,uint8_t type,const Bytes& payload,const Deadline& d){
    auto h=packetHeader(type,payload.size());c.write(h.data(),h.size(),d);c.write(payload.data(),payload.size(),d);
}
std::string pair(int port,const std::string& code,const Bytes& pem,int timeoutMs,uint64_t network){
    Deadline d(timeoutMs);
    require(code.size()==6 && std::all_of(code.begin(),code.end(),[](char c){return c>='0' && c<='9';}),
            "ADB_PAIRING_CODE_INVALID");
    auto c=connect(port,pem,d,network);c->upgrade(d);
    Secret password(code.begin(),code.end());auto exported=c->exporter();
    password.insert(password.end(),exported.begin(),exported.end());
    PairingAuth auth(true,password);sendPacket(*c,0,auth.message,d);
    auth.finish(receivePacket(*c,0,d));
    sendPacket(*c,1,auth.encrypt(hostPeerInfo(c->identity.key.get())),d);
    auto result=deviceGuid(auth.decrypt(receivePacket(*c,1,d)));d.remaining();return result;
}
static std::mutex handlesMutex;
static std::unordered_map<uint64_t,std::shared_ptr<Connection>> handles;
static uint64_t nextHandle=1;
uint64_t registerConnection(std::shared_ptr<Connection> c){
    std::lock_guard<std::mutex> lock(handlesMutex);
    require(nextHandle<=INT64_MAX,"ADB_HANDLE_EXHAUSTED");uint64_t id=nextHandle++;handles.emplace(id,std::move(c));return id;
}
std::shared_ptr<Connection> lookup(uint64_t id){
    std::lock_guard<std::mutex> lock(handlesMutex);auto it=handles.find(id);
    require(it!=handles.end(),"ADB_HANDLE_INVALID");return it->second;
}
void closeHandle(uint64_t id){
    std::shared_ptr<Connection> c;
    {std::lock_guard<std::mutex> lock(handlesMutex);auto it=handles.find(id);if(it==handles.end())return;c=std::move(it->second);handles.erase(it);}
    c->cancel(); // descriptor closes only after all in-flight holders release it
}
}
