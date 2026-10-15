package dev.wind.agent.runtime.linux;

import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Independent APK-pinned publisher root, using Android/JCA RSA-SHA256, never downloaded keys. */
public final class PublisherTrust {
    private PublisherTrust() {}
    public static String verify(byte[] envelopeBytes, byte[] manifest, String family, String release, byte[] roots, long now) throws Exception {
        if (envelopeBytes.length > 32768 || roots.length > 32768) throw new SecurityException("PUBLISHER_METADATA_TOO_LARGE");
        JSONObject envelope=new JSONObject(new String(envelopeBytes,StandardCharsets.UTF_8));
        JSONObject trust=new JSONObject(new String(roots,StandardCharsets.UTF_8));
        if(envelope.getInt("schema")!=1 || trust.getInt("schema")!=1 || !"RSA_SHA256".equals(envelope.getString("algorithm")))
            throw new SecurityException("PUBLISHER_FORMAT_INVALID");
        String id=envelope.getString("key_id");
        JSONArray keys=trust.getJSONArray("keys");
        JSONObject selected=null;
        for(int i=0;i<keys.length();i++) { JSONObject key=keys.getJSONObject(i);if(id.equals(key.getString("key_id"))) {
            if(selected!=null) throw new SecurityException("PUBLISHER_ROOT_DUPLICATE");selected=key;
        } }
        if(selected==null || selected.optBoolean("revoked",false)) throw new SecurityException("PUBLISHER_KEY_UNTRUSTED");
        byte[] encoded=Base64.getDecoder().decode(selected.getString("x509_base64"));
        PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        if(!(key instanceof RSAPublicKey) || ((RSAPublicKey)key).getModulus().bitLength()<3072)
            throw new SecurityException("PUBLISHER_KEY_WEAK");
        if(!id.equals("harnessmate-rsa-"+sha(encoded).substring(0,16))) throw new SecurityException("PUBLISHER_KEY_ID_MISMATCH");
        byte[] signed=Base64.getDecoder().decode(envelope.getString("signed"));
        if(signed.length>16384) throw new SecurityException("PUBLISHER_STATEMENT_TOO_LARGE");
        Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update(signed);
        if(!verifier.verify(Base64.getDecoder().decode(envelope.getString("signature")))) throw new SecurityException("PUBLISHER_SIGNATURE_INVALID");
        JSONObject statement=new JSONObject(new String(signed,StandardCharsets.UTF_8));
        JSONObject metadata=new JSONObject(new String(manifest,StandardCharsets.UTF_8));
        if(statement.getInt("schema")!=1 || !family.equals(statement.getString("family")) || !release.equals(statement.getString("release")) ||
            !sha(manifest).equals(statement.getString("manifest_sha256")) || !metadata.getString("sha256").equals(statement.getString("payload_sha256")) ||
            metadata.getLong("compressedBytes")!=statement.getLong("compressed_bytes")) throw new SecurityException("PUBLISHER_BINDING_MISMATCH");
        if(now<statement.getLong("not_before") || now>=statement.getLong("not_after")) throw new SecurityException("PUBLISHER_STATEMENT_EXPIRED");
        return id;
    }
    public static String sha(byte[] bytes) throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
}
