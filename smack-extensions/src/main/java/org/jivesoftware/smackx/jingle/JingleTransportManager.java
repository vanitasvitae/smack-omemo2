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
package org.jivesoftware.smackx.jingle;

import java.util.HashMap;
import java.util.WeakHashMap;

import org.jivesoftware.smack.Manager;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.jingle.exception.UnsupportedJingleTransportException;

/**
 * Manager for JingleContentTransportManagers.
 */
public final class JingleTransportManager extends Manager {

    public static final WeakHashMap<XMPPConnection, JingleTransportManager> INSTANCES = new WeakHashMap<>();

    private final HashMap<String, AbstractJingleContentTransportManager<?>> contentTransportManagers = new HashMap<>();

    private JingleTransportManager(XMPPConnection connection) {
        super(connection);
    }

    public static JingleTransportManager getInstanceFor(XMPPConnection connection) {
        JingleTransportManager manager = INSTANCES.get(connection);
        if (manager == null) {
            manager = new JingleTransportManager(connection);
            INSTANCES.put(connection, manager);
        }
        return manager;
    }

    public AbstractJingleContentTransportManager<?> getJingleContentTransportManager(String namespace) throws UnsupportedJingleTransportException {
        AbstractJingleContentTransportManager<?> manager = contentTransportManagers.get(namespace);
        if (manager == null) {
            throw new UnsupportedJingleTransportException("Cannot find registered JingleContentTransportManager for " + namespace);
        }
        return manager;
    }

    public void registerJingleContentTransportManager(AbstractJingleContentTransportManager<?> manager) {
        contentTransportManagers.put(manager.getNamespace(), manager);
    }

    public void unregisterJingleContentTransportManager(AbstractJingleContentTransportManager<?> manager) {
        contentTransportManagers.remove(manager.getNamespace());
    }

    public static String generateSessionId() {
        return StringUtils.randomString(24);
    }
}
