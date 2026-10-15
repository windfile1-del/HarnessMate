// SPDX-License-Identifier: Apache-2.0
// AOSP Android 14 pairing wire compatibility; see third_party/adb/source-lock.json.
#include "adb.h"
#include <algorithm>
#include <cstring>
#include <openssl/bn.h>
#include <openssl/buf.h>
#include <openssl/hkdf.h>
#include <openssl/mem.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <openssl/rsa.h>
#include <openssl/x509v3.h>
namespace vega::adb {
void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
Secret::~Secret() { if (!empty()) OPENSSL_cleanse(data(),size()); }
static int noPassword(char*,int,int,void*) { return 0; }
Identity::Identity(const Bytes& pem) {
    require(!pem.empty() && pem.size()<=16384,"ADB_IDENTITY_INVALID");
    bssl::UniquePtr<BIO> bio(BIO_new_mem_buf(pem.data(),static_cast<int>(pem.size())));
    require(bool(bio),"ADB_IDENTITY_INVALID");
    key.reset(PEM_read_bio_PrivateKey(bio.get(),nullptr,noPassword,nullptr));
    cert.reset(PEM_read_bio_X509(bio.get(),nullptr,nullptr,nullptr));
    require(key && cert && EVP_PKEY_id(key.get())==EVP_PKEY_RSA &&
            EVP_PKEY_bits(key.get())==2048 && X509_check_private_key(cert.get(),key.get())==1 &&
            X509_verify(cert.get(),key.get())==1,"ADB_IDENTITY_INVALID");
    const RSA* rsa=EVP_PKEY_get0_RSA(key.get());
    require(RSA_check_key(rsa)==1 && BN_is_word(RSA_get0_e(rsa),RSA_F4),"ADB_IDENTITY_INVALID");
}
Bytes generateIdentity() {
    bssl::UniquePtr<RSA> rsa(RSA_new());
    bssl::UniquePtr<BIGNUM> e(BN_new());
    bssl::UniquePtr<EVP_PKEY> key(EVP_PKEY_new());
    bssl::UniquePtr<X509> cert(X509_new());
    require(rsa && e && key && cert && BN_set_word(e.get(),RSA_F4)==1 &&
            RSA_generate_key_ex(rsa.get(),2048,e.get(),nullptr)==1 &&
            EVP_PKEY_set1_RSA(key.get(),rsa.get())==1,"ADB_IDENTITY_GENERATION_FAILED");
    require(X509_set_version(cert.get(),2)==1 &&
            ASN1_INTEGER_set(X509_get_serialNumber(cert.get()),1)==1 &&
            X509_gmtime_adj(X509_get_notBefore(cert.get()),-60) &&
            X509_gmtime_adj(X509_get_notAfter(cert.get()),10L*365*24*60*60) &&
            X509_set_pubkey(cert.get(),key.get())==1,"ADB_CERTIFICATE_FAILED");
    auto* name=X509_get_subject_name(cert.get());
    require(X509_NAME_add_entry_by_txt(name,"CN",MBSTRING_ASC,
            reinterpret_cast<const uint8_t*>("Adb"),-1,-1,0)==1 &&
            X509_set_issuer_name(cert.get(),name)==1,"ADB_CERTIFICATE_FAILED");
    X509V3_CTX ctx;
    X509V3_set_ctx_nodb(&ctx);
    X509V3_set_ctx(&ctx,cert.get(),cert.get(),nullptr,nullptr,0);
    for (auto extension : {std::pair<int,const char*>(NID_basic_constraints,"critical,CA:TRUE"),
                          {NID_key_usage,"critical,keyCertSign,cRLSign,digitalSignature"},
                          {NID_subject_key_identifier,"hash"}}) {
        std::string value(extension.second);
        bssl::UniquePtr<X509_EXTENSION> ext(X509V3_EXT_nconf_nid(nullptr,&ctx,extension.first,value.data()));
        require(ext && X509_add_ext(cert.get(),ext.get(),-1)==1,"ADB_CERTIFICATE_FAILED");
    }
    require(X509_sign(cert.get(),key.get(),EVP_sha256())>0,"ADB_CERTIFICATE_FAILED");
    bssl::UniquePtr<BIO> bio(BIO_new(BIO_s_mem()));
    require(bio && PEM_write_bio_PrivateKey(bio.get(),key.get(),nullptr,nullptr,0,nullptr,nullptr)==1 &&
            PEM_write_bio_X509(bio.get(),cert.get())==1,"ADB_PEM_FAILED");
    BUF_MEM* mem=nullptr;
    BIO_get_mem_ptr(bio.get(),&mem);
    require(mem && mem->length>0,"ADB_PEM_FAILED");
    Bytes result(mem->data,mem->data+mem->length);
    OPENSSL_cleanse(mem->data,mem->length);
    return result;
}
static void put32(uint8_t* p,uint32_t value) {
    for(int i=0;i<4;++i) p[i]=static_cast<uint8_t>(value>>(8*i));
}
Bytes hostPeerInfo(EVP_PKEY* key) {
    const RSA* rsa=EVP_PKEY_get0_RSA(key);
    require(rsa && RSA_bits(rsa)==2048,"ADB_RSA_KEY_INVALID");
    const BIGNUM* n=RSA_get0_n(rsa);
    bssl::UniquePtr<BN_CTX> ctx(BN_CTX_new());
    bssl::UniquePtr<BIGNUM> r(BN_new()),rr(BN_new()),mod(BN_new()),word(BN_new());
    require(ctx && r && rr && mod && word && BN_set_bit(mod.get(),32)==1 &&
            BN_mod(word.get(),n,mod.get(),ctx.get())==1,"ADB_RSA_ENCODING_FAILED");
    bssl::UniquePtr<BIGNUM> inverse(BN_mod_inverse(nullptr,word.get(),mod.get(),ctx.get()));
    require(inverse && BN_set_bit(r.get(),2048)==1 && BN_mod_sqr(rr.get(),r.get(),n,ctx.get())==1,
            "ADB_RSA_ENCODING_FAILED");
    Bytes encoded(524,0);
    put32(encoded.data(),64);
    put32(encoded.data()+4,0u-static_cast<uint32_t>(BN_get_word(inverse.get())));
    require(BN_bn2le_padded(encoded.data()+8,256,n)==1 &&
            BN_bn2le_padded(encoded.data()+264,256,rr.get())==1,"ADB_RSA_ENCODING_FAILED");
    put32(encoded.data()+520,static_cast<uint32_t>(BN_get_word(RSA_get0_e(rsa))));
    Bytes b64(4*((encoded.size()+2)/3)+1);
    int size=EVP_EncodeBlock(b64.data(),encoded.data(),encoded.size());
    const std::string comment=" vega@localhost";
    Bytes result(8192,0); // type 0: ADB RSA public key, not PEM or DER.
    std::copy_n(b64.data(),size,result.data()+1);
    std::copy(comment.begin(),comment.end(),result.begin()+1+size);
    return result;
}
Bytes signAuthToken(EVP_PKEY* key, const Bytes& token) {
    require(token.size()==20 && key && EVP_PKEY_id(key)==EVP_PKEY_RSA &&
            EVP_PKEY_bits(key)==2048,"ADB_AUTH_TOKEN_INVALID");
    RSA* rsa=const_cast<RSA*>(EVP_PKEY_get0_RSA(key));
    Bytes signature(RSA_size(rsa));unsigned int n=0;
    // AUTH TOKEN already contains the SHA1-sized challenge: do not hash it again.
    require(RSA_sign(NID_sha1,token.data(),token.size(),signature.data(),&n,rsa)==1 &&
            n==256,"ADB_AUTH_SIGNATURE_FAILED");
    signature.resize(n);return signature;
}
Bytes authPublicKey(EVP_PKEY* key) {
    auto peer=hostPeerInfo(key);
    auto end=std::find(peer.begin()+1,peer.end(),0);
    require(end!=peer.end(),"ADB_AUTH_PUBLIC_KEY_INVALID");
    return Bytes(peer.begin()+1,end+1); // Android binary RSA key base64 + comment + NUL.
}
std::string deviceGuid(const Bytes& peer) {
    require(peer.size()==8192 && peer[0]==1,"ADB_PEER_INFO_INVALID");
    auto end=std::find(peer.begin()+1,peer.end(),0);
    require(end!=peer.end() && end!=peer.begin()+1,"ADB_GUID_INVALID");
    for(auto p=peer.begin()+1;p!=end;++p)
        require((*p>='a' && *p<='z') || (*p>='A' && *p<='Z') ||
                (*p>='0' && *p<='9') || *p=='-' || *p=='_' || *p=='.',"ADB_GUID_INVALID");
    return std::string(peer.begin()+1,end);
}
uint32_t packetLength(const std::array<uint8_t,6>& h,uint8_t expected) {
    require(h[0]==1 && expected<=1 && h[1]==expected,"ADB_PAIRING_HEADER_INVALID");
    uint32_t n=(uint32_t(h[2])<<24)|(uint32_t(h[3])<<16)|(uint32_t(h[4])<<8)|h[5];
    require(n>0 && n<=16384,"ADB_PAIRING_LENGTH_INVALID");
    require((expected==0 && n<=SPAKE2_MAX_MSG_SIZE) || (expected==1 && n==8192+16),
            "ADB_PAIRING_LENGTH_INVALID");
    return n;
}
std::array<uint8_t,6> packetHeader(uint8_t type,size_t size) {
    require(size<=16384,"ADB_PAIRING_LENGTH_INVALID");
    std::array<uint8_t,6> h{1,type,uint8_t(size>>24),uint8_t(size>>16),uint8_t(size>>8),uint8_t(size)};
    packetLength(h,type);
    return h;
}
PairingAuth::PairingAuth(bool client,const Bytes& password) {
    static const uint8_t alice[]="adb pair client",bob[]="adb pair server";
    require(!password.empty(),"ADB_PAIRING_PASSWORD_INVALID");
    spake_.reset(SPAKE2_CTX_new(client?spake2_role_alice:spake2_role_bob,
            client?alice:bob,client?sizeof(alice):sizeof(bob),
            client?bob:alice,client?sizeof(bob):sizeof(alice)));
    message.resize(SPAKE2_MAX_MSG_SIZE);
    size_t n=0;
    require(spake_ && SPAKE2_generate_msg(spake_.get(),message.data(),&n,message.size(),
            password.data(),password.size())==1 && n>0,"ADB_SPAKE2_GENERATION_FAILED");
    message.resize(n);
}
void PairingAuth::finish(const Bytes& peer) {
    require(spake_ && !aead_ && !peer.empty() && peer.size()<=SPAKE2_MAX_MSG_SIZE,
            "ADB_SPAKE2_MESSAGE_INVALID");
    Secret material(SPAKE2_MAX_KEY_SIZE),key(16);
    size_t n=0;
    require(SPAKE2_process_msg(spake_.get(),material.data(),&n,material.size(),peer.data(),peer.size())==1,
            "ADB_SPAKE2_MESSAGE_INVALID");
    spake_.reset();
    static const uint8_t info[]="adb pairing_auth aes-128-gcm key";
    require(HKDF(key.data(),key.size(),EVP_sha256(),material.data(),n,nullptr,0,info,sizeof(info)-1)==1,
            "ADB_HKDF_FAILED");
    aead_.reset(EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(),key.data(),key.size(),EVP_AEAD_DEFAULT_TAG_LENGTH));
    require(bool(aead_),"ADB_AES_FAILED");
}
Bytes PairingAuth::encrypt(const Bytes& plain) {
    require(aead_ && !plain.empty() && enc_!=UINT64_MAX,"ADB_AES_STATE_INVALID");
    uint8_t nonce[12]={};
    for(int i=0;i<8;++i)nonce[i]=uint8_t(enc_>>(8*i));
    Bytes out(plain.size()+16);size_t n=0;
    require(EVP_AEAD_CTX_seal(aead_.get(),out.data(),&n,out.size(),nonce,sizeof(nonce),
            plain.data(),plain.size(),nullptr,0)==1,"ADB_AES_ENCRYPT_FAILED");
    ++enc_;out.resize(n);return out;
}
Bytes PairingAuth::decrypt(const Bytes& cipher) {
    require(aead_ && cipher.size()>=16 && dec_!=UINT64_MAX,"ADB_AES_STATE_INVALID");
    uint8_t nonce[12]={};
    for(int i=0;i<8;++i)nonce[i]=uint8_t(dec_>>(8*i));
    Bytes out(cipher.size());size_t n=0;
    require(EVP_AEAD_CTX_open(aead_.get(),out.data(),&n,out.size(),nonce,sizeof(nonce),
            cipher.data(),cipher.size(),nullptr,0)==1,"ADB_PAIRING_AUTH_FAILED");
    ++dec_;out.resize(n);return out;
}
}
