/**
 *
 * Copyright 2017 Paul Schaub
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.smackx.ciphers;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import javax.crypto.NoSuchPaddingException;

public class Aes256GcmNoPadding extends AesGcmNoPadding {
    public static final String NAMESPACE = "urn:xmpp:ciphers:aes-256-gcm-nopadding:0";

    public Aes256GcmNoPadding() throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException,
            NoSuchProviderException, InvalidAlgorithmParameterException {
        super(256);
    }

    public Aes256GcmNoPadding(byte[] keyAndIv) throws NoSuchProviderException, InvalidAlgorithmParameterException,
            NoSuchAlgorithmException, InvalidKeyException, NoSuchPaddingException {
        super(AesGcmNoPadding.copyOfRange(keyAndIv, 0, keyAndIv.length / 2), //Key
                AesGcmNoPadding.copyOfRange(keyAndIv, keyAndIv.length / 2, keyAndIv.length / 2)); //IV
    }

    @Override
    public String getNamespace() {
        return NAMESPACE;
    }
}
