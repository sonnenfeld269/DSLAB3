package security;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.util.encoders.Hex;

/**
 *
 * @author Dave
 */
public class HMAC {

    private static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(HMAC.class.getSimpleName());
    private String plaintextWithHmac = null;
    Mac hMac = null;
    Key secretKey = null;

    public HMAC(File key) throws HMACException {
        try {
            //secretKey = getKey("keys/Clients/alice.key");
            secretKey = getKey(key.getPath());
            hMac = Mac.getInstance("HmacSHA256");
            hMac.init(secretKey);
        } catch (InvalidKeyException ex) {
           throw new HMACException("InvalidKeyException"+ex.getMessage());
        } catch (NoSuchAlgorithmException ex) {
            throw new HMACException("NoSuchAlgorithmException"+ex.getMessage());
        }
    }

    /**
     * Generates an Hmac from a message. Also a string will be created
     * containing the message itself and the hash.
     *
     * @return Mac of the given message
     * @param message Message to which the hMac will be added
     */
    public byte[] generateHmac(String message) throws HMACException {
       
            
            byte[] messageBytes = message.getBytes();
            hMac.update(messageBytes);
            byte[] hmacByte = hMac.doFinal();
            hmacByte = Base64Encoder.encodeBase64(hmacByte);
            return hmacByte;
      
    }

    /**
     * Extracts the plaintext and hash from a string. Generates a new hMac of
     * the plaintext and compares it to the received hMac.
     *
     * @param plaintextWithHash String containing the plaintext and the hash in
     * form of a string.
     * @return true if byte arrays are equal. false otherwise.
     */
    public boolean validateHMac(String plaintext_,String Hashmac) throws HMACException {
        byte[] receivedHash = Base64Encoder.decodeBase64(Hashmac.getBytes());
        hMac.update(plaintext_.getBytes());
        byte[] computedHash = hMac.doFinal();
        boolean validHash = MessageDigest.isEqual(computedHash,receivedHash);
        return validHash;
    }

    private Key getKey(String path) throws HMACException {
        try {
            FileInputStream fis = new FileInputStream(path);
            byte[] keyBytes = new byte[1024];
            fis.read(keyBytes);
            fis.close();
            byte[] input = Hex.decode(keyBytes);
            return new SecretKeySpec(input, "HmacSHA256");
        } catch (IOException ex) {
            throw new HMACException("HMAC:IOException:" + ex.getMessage());
        }
    }

    public String getPlaintextWithHmac() {
        return plaintextWithHmac;
    }
}
