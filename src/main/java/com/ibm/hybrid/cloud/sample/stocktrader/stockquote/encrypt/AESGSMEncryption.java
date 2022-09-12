/*
       Copyright 2022 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.ibm.hybrid.cloud.sample.stocktrader.stockquote.encrypt;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

/** Written by Cristhian Aguilar (GitHub ID: sigfrido45) */
public class AESGSMEncryption {

    private static final String GSM_ALGORITHM = "AES/GCM/NoPadding";
    private Key key;
    private IvParameterSpec initialVector;
    private final String password;

    public AESGSMEncryption(String password) {
        this.password = password;
    }

    public String encrypt(String input) throws AESException {
        try {
            Cipher cipher = Cipher.getInstance(GSM_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), getInitialVector());
            byte[] cipherText = cipher.doFinal(input.getBytes());
            return new String(Base64.getEncoder().encode(cipherText));
        } catch (Exception e) {
            throw new AESException(e.getMessage());
        }
    }

    public String decrypt(String cipherTextInBase64) throws AESException {
        try {
            Cipher cipher = Cipher.getInstance(GSM_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, getKey(), getInitialVector());
            byte[] plainText = cipher.doFinal(Base64.getDecoder().decode(cipherTextInBase64.getBytes()));
            return new String(plainText);
        } catch (Exception e) {
            throw new AESException(e.getMessage());
        }
    }

    private Key getKey() throws NoSuchAlgorithmException, InvalidKeySpecException {
        if (key == null)
            key = getKeyFromPassword(password, generateSecureRandomBytes(8));
        return key;
    }

    private IvParameterSpec getInitialVector() {
        if (initialVector == null)
            initialVector = generateInitialVector(96);
        return initialVector;
    }

    private IvParameterSpec generateInitialVector(int byteNum) {
        return new IvParameterSpec(generateSecureRandomBytes(byteNum));
    }

    private Key getKeyFromPassword(String password, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private byte[] generateSecureRandomBytes(int byteNum) {
        byte[] bytes = new byte[byteNum];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}