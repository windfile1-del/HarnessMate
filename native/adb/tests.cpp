// SPDX-License-Identifier: Apache-2.0
#include "adb.h"
#include <adb/pairing/pairing_auth.h>
#include <algorithm>
#include <cstring>
#include <future>
#include <iostream>
#include <thread>
#include <openssl/bn.h>
#include <openssl/rsa.h>
#ifndef _WIN32
#include <arpa/inet.h>
#include <cerrno>
#include <sys/socket.h>
#endif
using namespace vega::adb;
static std::atomic<int> checks{0};
static void check(bool ok,const char* msg){++checks;require(ok,msg);}
template<class F>static void rejects(F f,const char* expected){
    bool failed=false;
    std::string actual="operation completed";
    try{f();}catch(const std::exception& e){actual=e.what();failed=actual.find(expected)!=std::string::npos;}
    auto message=std::string("Expected ")+expected+"; got "+actual;
    check(failed,message.c_str());
}
static void forceBackpressure(Connection& client,Connection& server){
    int size=4096;
    require(setsockopt(client.fd,SOL_SOCKET,SO_SNDBUF,reinterpret_cast<const char*>(&size),sizeof(size))==0 &&
            setsockopt(server.fd,SOL_SOCKET,SO_RCVBUF,reinterpret_cast<const char*>(&size),sizeof(size))==0,
            "fixture socket buffer");
    // Winsock may accept a single oversized send even with a small SO_SNDBUF.
    // Establish actual WOULDBLOCK before starting the operation's deadline.
    Bytes block(65536,42);size_t total=0;Deadline preparation(5000);
    while(total<64*1024*1024){
        preparation.remaining();
        int n=static_cast<int>(send(client.fd,reinterpret_cast<const char*>(block.data()),block.size(),0));
        if(n>0){total+=n;continue;}
#ifdef _WIN32
        int error=WSAGetLastError();
        if(n<0 && error==WSAEWOULDBLOCK){std::cout<<"backpressure primed "<<total<<" bytes\n"<<std::flush;return;}
#else
        if(n<0 && (errno==EAGAIN || errno==EWOULDBLOCK)){std::cout<<"backpressure primed "<<total<<" bytes\n"<<std::flush;return;}
#endif
        throw std::runtime_error("fixture backpressure send failed");
    }
    throw std::runtime_error("fixture did not reach WOULDBLOCK within 64MiB");
}
struct Listener {
    Socket fd=invalidSocket;int port=0;
    Listener(){
        initSockets();fd=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);require(fd!=invalidSocket,"listen socket");
        sockaddr_in a{};a.sin_family=AF_INET;a.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        require(bind(fd,reinterpret_cast<sockaddr*>(&a),sizeof(a))==0 && listen(fd,1)==0,"bind");
#ifdef _WIN32
        int n=sizeof(a);
#else
        socklen_t n=sizeof(a);
#endif
        require(getsockname(fd,reinterpret_cast<sockaddr*>(&a),&n)==0,"getsockname");port=ntohs(a.sin_port);
    }
    ~Listener(){closeSocket(fd);}
    std::shared_ptr<Connection> accept(const Bytes& pem){
        auto c=std::make_shared<Connection>(pem);c->fd=::accept(fd,nullptr,nullptr);
        require(c->fd!=invalidSocket,"accept");return c;
    }
};
static Bytes receive(Connection& c,int type,const Deadline& d){
    auto bytes=c.readFully(6,d);std::array<uint8_t,6> h{};std::copy(bytes.begin(),bytes.end(),h.begin());
    return c.readFully(packetLength(h,type),d);
}
static void sendPacket(Connection& c,int type,const Bytes& bytes,const Deadline& d){
    auto h=packetHeader(type,bytes.size());c.write(h.data(),h.size(),d);c.write(bytes.data(),bytes.size(),d);
}
struct ReferenceAuth {
    PairingAuthCtx* ctx;
    explicit ReferenceAuth(const Bytes& password):ctx(pairing_auth_server_new(password.data(),password.size())){require(ctx,"reference auth");}
    ~ReferenceAuth(){pairing_auth_destroy(ctx);}
    Bytes message(){Bytes m(pairing_auth_msg_size(ctx));pairing_auth_get_spake2_msg(ctx,m.data());return m;}
    void finish(const Bytes& peer){require(pairing_auth_init_cipher(ctx,peer.data(),peer.size()),"reference SPAKE");}
    Bytes decrypt(const Bytes& cipher){Bytes p(cipher.size());size_t n=p.size();require(pairing_auth_decrypt(ctx,cipher.data(),cipher.size(),p.data(),&n),"reference decrypt");p.resize(n);return p;}
    Bytes encrypt(const Bytes& plain){Bytes p(pairing_auth_safe_encrypted_size(ctx,plain.size()));size_t n=p.size();require(pairing_auth_encrypt(ctx,plain.data(),plain.size(),p.data(),&n),"reference encrypt");p.resize(n);return p;}
};
static uint32_t le32(const uint8_t* p){return uint32_t(p[0])|(uint32_t(p[1])<<8)|(uint32_t(p[2])<<16)|(uint32_t(p[3])<<24);}
static void verifyPublicKey(const Bytes& peer,const Identity& host){
    require(peer.size()==8192 && peer[0]==0,"host peer type");
    auto space=std::find(peer.begin()+1,peer.end(),' ');require(space!=peer.end(),"host key comment");
    // EVP_DecodeBase64 checks the padded upper bound (525), even though the
    // single '=' padding produces exactly 524 Android key bytes.
    Bytes encoded(528);size_t n=0;
    require(EVP_DecodeBase64(encoded.data(),&n,encoded.size(),peer.data()+1,space-(peer.begin()+1))==1 && n==524,"host base64");
    require(le32(encoded.data())==64 && le32(encoded.data()+520)==65537,"host key words/exponent");
    bssl::UniquePtr<BIGNUM> modulus(BN_le2bn(encoded.data()+8,256,nullptr));
    require(modulus && BN_cmp(modulus.get(),RSA_get0_n(EVP_PKEY_get0_RSA(host.key.get())))==0,"host key modulus");
    uint64_t low=le32(encoded.data()+8),inv=le32(encoded.data()+4);
    require(uint32_t(low*inv)==UINT32_MAX,"host n0inv");
    bssl::UniquePtr<BN_CTX> ctx(BN_CTX_new());
    bssl::UniquePtr<BIGNUM> power(BN_new()),rr(BN_new()),wire(BN_le2bn(encoded.data()+264,256,nullptr));
    require(BN_set_bit(power.get(),4096)==1 && BN_mod(rr.get(),power.get(),modulus.get(),ctx.get())==1 && BN_cmp(rr.get(),wire.get())==0,"host rr");
}
enum class Scenario {Good,WrongCode,BadVersion,BadType,BadLength,SlowHeader,TruncatedHeader,
                     TruncatedBody,BadSpake,BadGuidType,BadGuidNoNul,BadTag};
static void pairingServer(Connection& c,const Bytes& hostPem,Scenario scenario){
    Deadline d(3000);c.upgrade(d,true);
    auto exported=c.exporter();Bytes password={'1','2','3','4','5','6'};
    password.insert(password.end(),exported.begin(),exported.end());ReferenceAuth reference(password);
    auto client=receive(c,0,d);auto msg=reference.message();
    if(scenario==Scenario::TruncatedHeader){auto h=packetHeader(0,msg.size());c.write(h.data(),3,d);return;}
    if(scenario==Scenario::TruncatedBody){auto h=packetHeader(0,msg.size());c.write(h.data(),h.size(),d);c.write(msg.data(),3,d);return;}
    if(scenario==Scenario::BadSpake){sendPacket(c,0,Bytes(1,0),d);return;}
    if(scenario==Scenario::BadVersion || scenario==Scenario::BadType || scenario==Scenario::BadLength || scenario==Scenario::SlowHeader){
        auto h=packetHeader(0,msg.size());
        if(scenario==Scenario::BadVersion)h[0]=2;
        if(scenario==Scenario::BadType)h[1]=1;
        if(scenario==Scenario::BadLength){h[2]=0xff;h[3]=0xff;h[4]=0xff;h[5]=0xff;}
        if(scenario==Scenario::SlowHeader){for(int i=0;i<6;++i){try{c.write(&h[i],1,d);}catch(...){break;}std::this_thread::sleep_for(std::chrono::milliseconds(55));}return;}
        c.write(h.data(),h.size(),d);return;
    }
    reference.finish(client);sendPacket(c,0,msg,d);
    auto encrypted=receive(c,1,d);
    if(scenario==Scenario::WrongCode){
        rejects([&]{reference.decrypt(encrypted);},"reference decrypt");return;
    }
    auto host=reference.decrypt(encrypted);Identity id(hostPem);verifyPublicKey(host,id);
    Bytes guid(8192,0);guid[0]=1;std::string value="adb-test-device-012345";
    std::copy(value.begin(),value.end(),guid.begin()+1);
    if(scenario==Scenario::BadGuidType)guid[0]=0;
    if(scenario==Scenario::BadGuidNoNul)std::fill(guid.begin()+1,guid.end(),'a');
    auto packet=reference.encrypt(guid);
    if(scenario==Scenario::BadTag)packet.back()^=1;
    sendPacket(c,1,packet,d);
}
static void pairingTests(const Bytes& pem){
    for(auto s:{Scenario::Good,Scenario::WrongCode,Scenario::BadVersion,Scenario::BadType,
                Scenario::BadLength,Scenario::SlowHeader,Scenario::TruncatedHeader,Scenario::TruncatedBody,
                Scenario::BadSpake,Scenario::BadGuidType,Scenario::BadGuidNoNul,Scenario::BadTag}){
        std::cout<<"TLS AOSP pairing scenario "<<int(s)<<"\n"<<std::flush;
        Listener l;
        auto server=std::async(std::launch::async,[&]{auto c=l.accept(pem);pairingServer(*c,pem,s);});
        auto start=std::chrono::steady_clock::now();
        if(s==Scenario::Good)check(pair(l.port,"123456",pem,2000)=="adb-test-device-012345","TLS AOSP pairing GUID");
        else {
            const char* expected=s==Scenario::SlowHeader?"ADB_TIMEOUT":
                s==Scenario::WrongCode?"ADB_TLS_READ_FAILED":
                (s==Scenario::TruncatedHeader || s==Scenario::TruncatedBody)?"ADB_TLS_READ_FAILED":
                s==Scenario::BadSpake?"ADB_SPAKE2_MESSAGE_INVALID":
                (s==Scenario::BadVersion || s==Scenario::BadType)?"ADB_PAIRING_HEADER_INVALID":
                s==Scenario::BadLength?"ADB_PAIRING_LENGTH_INVALID":
                s==Scenario::BadGuidType?"ADB_PEER_INFO_INVALID":
                s==Scenario::BadGuidNoNul?"ADB_GUID_INVALID":"ADB_PAIRING_AUTH_FAILED";
            rejects([&]{pair(l.port,s==Scenario::WrongCode?"654321":"123456",pem,s==Scenario::SlowHeader?180:2000);},expected);
        }
        if(s==Scenario::SlowHeader)check(std::chrono::steady_clock::now()-start<std::chrono::milliseconds(400),"entire pairing timeout");
        server.get();
    }
}
static void cryptoTests(const Bytes& pem){
    Identity identity(pem);check(EVP_PKEY_bits(identity.key.get())==2048,"RSA2048");
    check(X509_verify(identity.cert.get(),identity.key.get())==1,"self-signed X509");
    verifyPublicKey(hostPeerInfo(identity.key.get()),identity);++checks;
    Bytes token(20);for(size_t n=0;n<token.size();++n)token[n]=uint8_t(n+1);
    auto signature=signAuthToken(identity.key.get(),token);
    auto rsa=const_cast<RSA*>(EVP_PKEY_get0_RSA(identity.key.get()));
    check(signature.size()==256 && RSA_verify(NID_sha1,token.data(),token.size(),signature.data(),signature.size(),rsa)==1,
          "classic AUTH signature matches Android SHA1-sized token without rehash");
    auto changed=token;changed[0]^=1;
    check(RSA_verify(NID_sha1,changed.data(),changed.size(),signature.data(),signature.size(),rsa)==0,"different AUTH challenge rejected");
    auto pub=authPublicKey(identity.key.get());auto peer=hostPeerInfo(identity.key.get());
    check(pub.back()==0 && Bytes(peer.begin()+1,peer.begin()+1+pub.size())==pub,"classic AUTH Android public key plus NUL");
    rejects([&]{signAuthToken(identity.key.get(),Bytes(19));},"ADB_AUTH_TOKEN_INVALID");
    rejects([&]{Identity bad(Bytes{'x'});},"ADB_IDENTITY_INVALID");
    Bytes password={'1','2','3','4','5','6'};password.resize(70,42);
    PairingAuth client(true,password);ReferenceAuth server(password);
    auto sm=server.message();server.finish(client.message);client.finish(sm);
    for(int n=0;n<3;++n){Bytes plain(8192,uint8_t(n+1));check(server.decrypt(client.encrypt(plain))==plain,"AOSP decrypt sequence");check(client.decrypt(server.encrypt(plain))==plain,"AOSP encrypt sequence");}
    PairingAuth wrong(true,Bytes{'w','r','o','n','g'});ReferenceAuth correct(password);
    auto cm=correct.message();correct.finish(wrong.message);wrong.finish(cm);
    rejects([&]{wrong.decrypt(correct.encrypt(Bytes(8192,1)));},"ADB_PAIRING_AUTH_FAILED");
    PairingAuth bad(true,password);rejects([&]{bad.finish(Bytes(1024,0));},"ADB_SPAKE2_MESSAGE_INVALID");
    rejects([&]{client.finish(sm);},"ADB_SPAKE2_MESSAGE_INVALID");
    auto cipher=server.encrypt(Bytes(8192,9));cipher.back()^=1;
    rejects([&]{client.decrypt(cipher);},"ADB_PAIRING_AUTH_FAILED");
    rejects([&]{packetLength({1,0,0,0,0,0},0);},"ADB_PAIRING_LENGTH_INVALID");
    rejects([&]{packetLength({1,2,0,0,0,32},0);},"ADB_PAIRING_HEADER_INVALID");
    rejects([&]{packetLength({1,1,0,0,0,16},1);},"ADB_PAIRING_LENGTH_INVALID");
    check(packetLength(packetHeader(1,8208),1)==8208,"big-endian packet header");
}
static void transportTests(const Bytes& pem){
    rejects([&]{connect(0,pem,Deadline(100));},"ADB_PORT_INVALID");
    rejects([&]{Deadline d(0);},"ADB_TIMEOUT_INVALID");
    rejects([&]{connect(5555,pem,Deadline(100),100);},"ADB_NETWORK_BIND_UNSUPPORTED");
    {
        Listener l;auto server=std::async(std::launch::async,[&]{auto c=l.accept(pem);Deadline d(2000);auto data=c->readFully(4,d);require(data==Bytes({1,2,3,4}),"plain offset write");c->write(data.data(),data.size(),d);});
        auto c=connect(l.port,pem,Deadline(2000));Bytes data{9,1,2,3,4,9};c->write(data.data()+1,4,Deadline(1000));
        Bytes read(10,7);int n=c->read(read.data()+3,4,Deadline(1000));
        check(n==4 && read[2]==7 && read[3]==1 && read[6]==4 && read[7]==7,"plain offset read");
        server.get();check(c->read(read.data(),1,Deadline(1000))==-1,"plain EOF");
    }
    {
        Listener l;auto accepted=std::async(std::launch::async,[&]{return l.accept(pem);});
        auto c=connect(l.port,pem,Deadline(1000));auto s=accepted.get();
        auto id=registerConnection(c);auto start=std::chrono::steady_clock::now();
        auto reader=std::async(std::launch::async,[&]{uint8_t b;rejects([&]{c->read(&b,1,Deadline(5000));},"ADB_CLOSED");});
        std::this_thread::sleep_for(std::chrono::milliseconds(40));closeHandle(id);closeHandle(id);reader.get();
        check(std::chrono::steady_clock::now()-start<std::chrono::milliseconds(300),"close cancels reader");
        rejects([&]{lookup(id);},"ADB_HANDLE_INVALID");
    }
    {
        Listener l;auto accepted=std::async(std::launch::async,[&]{return l.accept(pem);});
        auto c=connect(l.port,pem,Deadline(1000));auto s=accepted.get();uint8_t b;
        auto start=std::chrono::steady_clock::now();rejects([&]{c->read(&b,1,Deadline(80));},"ADB_TIMEOUT");
        check(std::chrono::steady_clock::now()-start<std::chrono::milliseconds(250),"read deadline");
    }
    {
        Listener l;auto accepted=std::async(std::launch::async,[&]{return l.accept(pem);});
        auto c=connect(l.port,pem,Deadline(1000));auto s=accepted.get();
        rejects([&]{c->upgrade(Deadline(100));},"ADB_TIMEOUT");
    }
    {
        Listener l;auto accepted=std::async(std::launch::async,[&]{return l.accept(pem);});
        auto c=connect(l.port,pem,Deadline(1000));auto s=accepted.get();
        forceBackpressure(*c,*s);
        Bytes huge(16*1024*1024,42);auto start=std::chrono::steady_clock::now();
        rejects([&]{c->write(huge.data(),huge.size(),Deadline(80));},"ADB_TIMEOUT");
        check(std::chrono::steady_clock::now()-start<std::chrono::milliseconds(300),"write deadline");
    }
    {
        Listener l;auto accepted=std::async(std::launch::async,[&]{return l.accept(pem);});
        auto c=connect(l.port,pem,Deadline(1000));auto s=accepted.get();auto id=registerConnection(c);
        forceBackpressure(*c,*s);
        Bytes huge(16*1024*1024,42);
        auto writer=std::async(std::launch::async,[&]{rejects([&]{c->write(huge.data(),huge.size(),Deadline(5000));},"ADB_CLOSED");});
        std::this_thread::sleep_for(std::chrono::milliseconds(60));auto start=std::chrono::steady_clock::now();
        closeHandle(id);writer.get();check(std::chrono::steady_clock::now()-start<std::chrono::milliseconds(300),"close cancels writer");
    }
    {
        Listener l;auto accepted=std::async(std::launch::async,[&]{return l.accept(pem);});
        auto c=connect(l.port,pem,Deadline(1000));auto s=accepted.get();auto id=registerConnection(c);
        auto tls=std::async(std::launch::async,[&]{rejects([&]{c->upgrade(Deadline(5000));},"ADB_CLOSED");});
        std::this_thread::sleep_for(std::chrono::milliseconds(40));closeHandle(id);tls.get();
    }
    {
        Listener l;auto server=std::async(std::launch::async,[&]{auto c=l.accept(pem);c->upgrade(Deadline(2000),true);uint8_t b=55;c->write(&b,1,Deadline(1000));auto response=c->readFully(1,Deadline(1000));require(response[0]==66,"TLS bidirectional");});
        auto c=connect(l.port,pem,Deadline(2000));c->upgrade(Deadline(2000));
        auto reader=std::async(std::launch::async,[&]{uint8_t b;c->read(&b,1,Deadline(1000));return b;});
        uint8_t b=66;c->write(&b,1,Deadline(1000));check(reader.get()==55,"TLS read write concurrency");server.get();
    }
}
int main(){
    try{auto pem=generateIdentity();cryptoTests(pem);std::cout<<"crypto PASS\n"<<std::flush;
        transportTests(pem);std::cout<<"transport PASS\n"<<std::flush;pairingTests(pem);
        std::cout<<"PASS "<<checks<<" checks; RSA AUTH + AOSP crypto + TLS1.3 loopback pairing, framing, deadlines, close\n";return 0;
    }catch(const std::exception& e){std::cerr<<"FAIL after "<<checks<<": "<<e.what()<<"\n";return 1;}
}
