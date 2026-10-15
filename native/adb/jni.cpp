// SPDX-License-Identifier: Apache-2.0
#include "adb.h"
#include <jni.h>
#include <algorithm>
#include <openssl/mem.h>
using namespace vega::adb;
static void error(JNIEnv* env,const std::exception& e){
    if(!env->ExceptionCheck()){auto cls=env->FindClass("java/io/IOException");if(cls)env->ThrowNew(cls,e.what());}
}
static Secret identity(JNIEnv* env,jbyteArray a){
    require(a!=nullptr,"ADB_IDENTITY_INVALID");auto n=env->GetArrayLength(a);
    require(n>0 && n<=16384,"ADB_IDENTITY_INVALID");Secret out(n);
    env->GetByteArrayRegion(a,0,n,reinterpret_cast<jbyte*>(out.data()));
    require(!env->ExceptionCheck(),"ADB_JNI_COPY_FAILED");return out;
}
static void range(JNIEnv* env,jbyteArray a,jint offset,jint length){
    require(a!=nullptr,"ADB_BUFFER_INVALID");auto n=env->GetArrayLength(a);
    require(offset>=0 && length>=0 && offset<=n && length<=n-offset && length<=16*1024*1024,
            "ADB_BUFFER_RANGE_INVALID");
}
extern "C" {
JNIEXPORT jbyteArray JNICALL Java_dev_wind_agent_adb_NativeAdb_nativeSignToken(JNIEnv* env,jobject,jlong handle,jbyteArray token){
    try{require(token && env->GetArrayLength(token)==20,"ADB_AUTH_TOKEN_INVALID");Bytes raw(20);
        env->GetByteArrayRegion(token,0,20,reinterpret_cast<jbyte*>(raw.data()));
        require(!env->ExceptionCheck(),"ADB_JNI_COPY_FAILED");auto c=lookup(handle);
        require(!c->cancelled.load(),"ADB_CLOSED");auto signature=signAuthToken(c->identity.key.get(),raw);
        auto result=env->NewByteArray(signature.size());if(result)env->SetByteArrayRegion(result,0,signature.size(),reinterpret_cast<const jbyte*>(signature.data()));return result;
    }catch(const std::exception& e){error(env,e);return nullptr;}
}
JNIEXPORT jbyteArray JNICALL Java_dev_wind_agent_adb_NativeAdb_nativePublicKey(JNIEnv* env,jobject,jlong handle){
    try{auto c=lookup(handle);require(!c->cancelled.load(),"ADB_CLOSED");auto key=authPublicKey(c->identity.key.get());
        auto result=env->NewByteArray(key.size());if(result)env->SetByteArrayRegion(result,0,key.size(),reinterpret_cast<const jbyte*>(key.data()));return result;
    }catch(const std::exception& e){error(env,e);return nullptr;}
}
JNIEXPORT jbyteArray JNICALL Java_dev_wind_agent_adb_NativeAdb_nativeGenerateIdentity(JNIEnv* env,jobject){
    try{auto raw=generateIdentity();Secret bytes(raw.begin(),raw.end());OPENSSL_cleanse(raw.data(),raw.size());
        auto result=env->NewByteArray(bytes.size());if(result)env->SetByteArrayRegion(result,0,bytes.size(),reinterpret_cast<const jbyte*>(bytes.data()));return result;
    }catch(const std::exception& e){error(env,e);return nullptr;}
}
JNIEXPORT jlong JNICALL Java_dev_wind_agent_adb_NativeAdb_nativeOpen(JNIEnv* env,jobject,jint port,jbyteArray pem,jint timeout,jlong network){
    try{require(network>=0,"ADB_NETWORK_HANDLE_INVALID");Deadline d(timeout);auto bytes=identity(env,pem);return registerConnection(connect(port,bytes,d,static_cast<uint64_t>(network)));}
    catch(const std::exception& e){error(env,e);return 0;}
}
JNIEXPORT void JNICALL Java_dev_wind_agent_adb_NativeAdb_nativeUpgradeTls(JNIEnv* env,jobject,jlong handle,jint timeout){
    try{Deadline d(timeout);lookup(handle)->upgrade(d);}catch(const std::exception& e){error(env,e);}
}
JNIEXPORT jint JNICALL Java_dev_wind_agent_adb_NativeAdb_nativeRead(JNIEnv* env,jobject,jlong handle,jbyteArray data,jint offset,jint length,jint timeout){
    try{Deadline d(timeout);range(env,data,offset,length);auto c=lookup(handle);
        Bytes bytes(std::min(length,64*1024));int n=c->read(bytes.data(),bytes.size(),d);
        if(n>0)env->SetByteArrayRegion(data,offset,n,reinterpret_cast<const jbyte*>(bytes.data()));return n;
    }catch(const std::exception& e){error(env,e);return -1;}
}
JNIEXPORT void JNICALL Java_dev_wind_agent_adb_NativeAdb_nativeWrite(JNIEnv* env,jobject,jlong handle,jbyteArray data,jint offset,jint length,jint timeout){
    try{Deadline d(timeout);range(env,data,offset,length);auto c=lookup(handle);Bytes bytes(length);
        env->GetByteArrayRegion(data,offset,length,reinterpret_cast<jbyte*>(bytes.data()));
        require(!env->ExceptionCheck(),"ADB_JNI_COPY_FAILED");c->write(bytes.data(),bytes.size(),d);
    }catch(const std::exception& e){error(env,e);}
}
JNIEXPORT void JNICALL Java_dev_wind_agent_adb_NativeAdb_nativeClose(JNIEnv* env,jobject,jlong handle){
    try{closeHandle(handle);}catch(const std::exception& e){error(env,e);}
}
JNIEXPORT jstring JNICALL Java_dev_wind_agent_adb_NativeAdb_nativePair(JNIEnv* env,jobject,jint port,jstring code,jbyteArray pem,jint timeout,jlong network){
    try{require(network>=0,"ADB_NETWORK_HANDLE_INVALID");Deadline d(timeout);require(code && env->GetStringLength(code)==6,"ADB_PAIRING_CODE_INVALID");
        jchar chars[6];env->GetStringRegion(code,0,6,chars);require(!env->ExceptionCheck(),"ADB_JNI_COPY_FAILED");
        std::string password;for(jchar c:chars){require(c>='0' && c<='9',"ADB_PAIRING_CODE_INVALID");password+=char(c);}
        auto bytes=identity(env,pem);auto guid=pair(port,password,bytes,d.remaining(),static_cast<uint64_t>(network));
        d.remaining();return env->NewStringUTF(guid.c_str());
    }catch(const std::exception& e){error(env,e);return nullptr;}
}
}
