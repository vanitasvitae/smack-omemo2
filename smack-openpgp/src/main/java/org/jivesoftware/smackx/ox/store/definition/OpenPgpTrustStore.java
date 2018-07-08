/**
 *
 * Copyright 2018 Paul Schaub.
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
package org.jivesoftware.smackx.ox.store.definition;

import java.io.IOException;

import org.jxmpp.jid.BareJid;
import org.pgpainless.pgpainless.key.OpenPgpV4Fingerprint;

public interface OpenPgpTrustStore {

    Trust getTrust(BareJid owner, OpenPgpV4Fingerprint fingerprint) throws IOException;

    void setTrust(BareJid owner, OpenPgpV4Fingerprint fingerprint, Trust trust) throws IOException;

    enum Trust {
        trusted,
        untrusted,
        undecided
    }
}
