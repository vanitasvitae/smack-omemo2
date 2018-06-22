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
package org.jivesoftware.smackx.ox.util;

import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.StanzaError;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.ox.OpenPgpManager;
import org.jivesoftware.smackx.ox.OpenPgpV4Fingerprint;
import org.jivesoftware.smackx.ox.element.PubkeyElement;
import org.jivesoftware.smackx.ox.element.PublicKeysListElement;
import org.jivesoftware.smackx.ox.element.SecretkeyElement;
import org.jivesoftware.smackx.pubsub.AccessModel;
import org.jivesoftware.smackx.pubsub.ConfigureForm;
import org.jivesoftware.smackx.pubsub.Item;
import org.jivesoftware.smackx.pubsub.LeafNode;
import org.jivesoftware.smackx.pubsub.PayloadItem;
import org.jivesoftware.smackx.pubsub.PubSubException;
import org.jivesoftware.smackx.pubsub.PubSubManager;
import org.jivesoftware.smackx.xdata.packet.DataForm;

import org.jxmpp.jid.BareJid;

public class PubSubDelegate {

    private static final Logger LOGGER = Logger.getLogger(PubSubDelegate.class.getName());

    /**
     * Name of the OX metadata node.
     *
     * @see <a href="https://xmpp.org/extensions/xep-0373.html#announcing-pubkey-list">XEP-0373 §4.2</a>
     */
    public static final String PEP_NODE_PUBLIC_KEYS = "urn:xmpp:openpgp:0:public-keys";

    /**
     * Name of the OX secret key node.
     * TODO: Update once my PR gets merged.
     * @see <a href="https://github.com/xsf/xeps/pull/669">xsf/xeps#669</a>
     */
    public static final String PEP_NODE_SECRET_KEY = "urn:xmpp:openpgp:secret-key:0";

    /**
     * Feature to be announced using the {@link ServiceDiscoveryManager} to subscribe to the OX metadata node.
     *
     * @see <a href="https://xmpp.org/extensions/xep-0373.html#pubsub-notifications">XEP-0373 §4.4</a>
     */
    public static final String PEP_NODE_PUBLIC_KEYS_NOTIFY = PEP_NODE_PUBLIC_KEYS + "+notify";

    /**
     * Name of the OX public key node, which contains the key with id {@code id}.
     *
     * @param id upper case hex encoded OpenPGP v4 fingerprint of the key.
     * @return PEP node name.
     */
    public static String PEP_NODE_PUBLIC_KEY(OpenPgpV4Fingerprint id) {
        return PEP_NODE_PUBLIC_KEYS + ":" + id;
    }

    /**
     * Query the access model of {@code node}. If it is different from {@code accessModel}, change the access model
     * of the node to {@code accessModel}.
     *
     * @see <a href="https://xmpp.org/extensions/xep-0060.html#accessmodels">XEP-0060 §4.5 - Node Access Models</a>
     *
     * @param node {@link LeafNode} whose PubSub access model we want to change
     * @param accessModel new access model.
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws InterruptedException
     * @throws SmackException.NoResponseException
     */
    public static void changeAccessModelIfNecessary(LeafNode node, AccessModel accessModel)
            throws XMPPException.XMPPErrorException, SmackException.NotConnectedException, InterruptedException,
            SmackException.NoResponseException {
        ConfigureForm current = node.getNodeConfiguration();
        if (current.getAccessModel() != accessModel) {
            ConfigureForm updateConfig = new ConfigureForm(DataForm.Type.submit);
            updateConfig.setAccessModel(accessModel);
            node.sendConfigurationForm(updateConfig);
        }
    }

    /**
     * Publish the users OpenPGP public key to the public key node if necessary.
     * Also announce the key to other users by updating the metadata node.
     *
     * @see <a href="https://xmpp.org/extensions/xep-0373.html#annoucning-pubkey">XEP-0373 §4.1</a>
     *
     * @throws InterruptedException
     * @throws PubSubException.NotALeafNodeException
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws SmackException.NoResponseException
     */
    public static void publishPublicKey(XMPPConnection connection, PubkeyElement pubkeyElement, OpenPgpV4Fingerprint fingerprint)
            throws InterruptedException, PubSubException.NotALeafNodeException,
            XMPPException.XMPPErrorException, SmackException.NotConnectedException, SmackException.NoResponseException {

        String keyNodeName = PEP_NODE_PUBLIC_KEY(fingerprint);
        PubSubManager pm = PubSubManager.getInstance(connection, connection.getUser().asBareJid());

        // Check if key available at data node
        // If not, publish key to data node
        LeafNode keyNode = pm.getOrCreateLeafNode(keyNodeName);
        changeAccessModelIfNecessary(keyNode, AccessModel.open);
        List<Item> items = keyNode.getItems(1);
        if (items.isEmpty()) {
            LOGGER.log(Level.FINE, "Node " + keyNodeName + " is empty. Publish.");
            keyNode.publish(new PayloadItem<>(pubkeyElement));
        } else {
            LOGGER.log(Level.FINE, "Node " + keyNodeName + " already contains key. Skip.");
        }

        // Fetch IDs from metadata node
        LeafNode metadataNode = pm.getOrCreateLeafNode(PEP_NODE_PUBLIC_KEYS);
        changeAccessModelIfNecessary(metadataNode, AccessModel.open);
        List<PayloadItem<PublicKeysListElement>> metadataItems = metadataNode.getItems(1);

        PublicKeysListElement.Builder builder = PublicKeysListElement.builder();
        if (!metadataItems.isEmpty() && metadataItems.get(0).getPayload() != null) {
            // Add old entries back to list.
            PublicKeysListElement publishedList = metadataItems.get(0).getPayload();
            for (PublicKeysListElement.PubkeyMetadataElement meta : publishedList.getMetadata().values()) {
                builder.addMetadata(meta);
            }
        }
        builder.addMetadata(new PublicKeysListElement.PubkeyMetadataElement(fingerprint, new Date()));

        // Publish IDs to metadata node
        metadataNode.publish(new PayloadItem<>(builder.build()));
    }

    /**
     * Consult the public key metadata node and fetch a list of all of our published OpenPGP public keys.
     * TODO: Add @see which points to the (for now missing) respective example in XEP-0373.
     *
     * @return content of our metadata node.
     * @throws InterruptedException
     * @throws PubSubException.NotALeafNodeException
     * @throws SmackException.NoResponseException
     * @throws SmackException.NotConnectedException
     * @throws XMPPException.XMPPErrorException
     * @throws PubSubException.NotAPubSubNodeException
     */
    public static PublicKeysListElement fetchPubkeysList(XMPPConnection connection)
            throws InterruptedException, PubSubException.NotALeafNodeException, SmackException.NoResponseException,
            SmackException.NotConnectedException, XMPPException.XMPPErrorException,
            PubSubException.NotAPubSubNodeException {
        return fetchPubkeysList(connection, connection.getUser().asBareJid());
    }

    /**
     * Consult the public key metadata node of {@code contact} to fetch the list of their published OpenPGP public keys.
     * TODO: Add @see which points to the (for now missing) respective example in XEP-0373.
     *
     * @param contact {@link BareJid} of the user we want to fetch the list from.
     * @return content of {@code contact}'s metadata node.
     * @throws InterruptedException
     * @throws PubSubException.NotALeafNodeException
     * @throws SmackException.NoResponseException
     * @throws SmackException.NotConnectedException
     * @throws XMPPException.XMPPErrorException
     * @throws PubSubException.NotAPubSubNodeException
     */
    public static PublicKeysListElement fetchPubkeysList(XMPPConnection connection, BareJid contact)
            throws InterruptedException, PubSubException.NotALeafNodeException, SmackException.NoResponseException,
            SmackException.NotConnectedException, XMPPException.XMPPErrorException,
            PubSubException.NotAPubSubNodeException {
        PubSubManager pm = PubSubManager.getInstance(connection, contact);

        LeafNode node = pm.getLeafNode(PEP_NODE_PUBLIC_KEYS);
        List<PayloadItem<PublicKeysListElement>> list = node.getItems(1);

        if (list.isEmpty()) {
            return null;
        }

        return list.get(0).getPayload();
    }

    /**
     * Delete our metadata node.
     *
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws InterruptedException
     * @throws SmackException.NoResponseException
     */
    public static void deletePubkeysListNode(XMPPConnection connection)
            throws XMPPException.XMPPErrorException, SmackException.NotConnectedException, InterruptedException,
            SmackException.NoResponseException {
        PubSubManager pm = PubSubManager.getInstance(connection, connection.getUser().asBareJid());
        try {
            pm.deleteNode(PEP_NODE_PUBLIC_KEYS);
        } catch (XMPPException.XMPPErrorException e) {
            if (e.getXMPPError().getCondition() == StanzaError.Condition.item_not_found) {
                LOGGER.log(Level.FINE, "Node does not exist. No need to delete it.");
            } else {
                throw e;
            }
        }
    }

    /**
     * Delete the public key node of the key with fingerprint {@code fingerprint}.
     *
     * @param connection
     * @param fingerprint
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws InterruptedException
     * @throws SmackException.NoResponseException
     */
    public static void deletePublicKeyNode(XMPPConnection connection, OpenPgpV4Fingerprint fingerprint)
            throws XMPPException.XMPPErrorException, SmackException.NotConnectedException, InterruptedException,
            SmackException.NoResponseException {
        PubSubManager pm = PubSubManager.getInstance(connection, connection.getUser().asBareJid());
        try {
            pm.deleteNode(PEP_NODE_PUBLIC_KEY(fingerprint));
        } catch (XMPPException.XMPPErrorException e) {
            if (e.getXMPPError().getCondition() == StanzaError.Condition.item_not_found) {
                LOGGER.log(Level.FINE, "Node does not exist. No need to delete it.");
            } else {
                throw e;
            }
        }
    }

    /**
     * Fetch the OpenPGP public key of a {@code contact}, identified by its OpenPGP {@code v4_fingerprint}.
     *
     * @see <a href="https://xmpp.org/extensions/xep-0373.html#discover-pubkey">XEP-0373 §4.3</a>
     *
     * @param contact {@link BareJid} of the contact we want to fetch a key from.
     * @param v4_fingerprint upper case, hex encoded v4 fingerprint of the contacts key.
     * @return {@link PubkeyElement} containing the requested public key.
     * @throws InterruptedException
     * @throws PubSubException.NotALeafNodeException
     * @throws SmackException.NoResponseException
     * @throws SmackException.NotConnectedException
     * @throws XMPPException.XMPPErrorException
     * @throws PubSubException.NotAPubSubNodeException
     */
    public static PubkeyElement fetchPubkey(XMPPConnection connection, BareJid contact, OpenPgpV4Fingerprint v4_fingerprint)
            throws InterruptedException, PubSubException.NotALeafNodeException, SmackException.NoResponseException,
            SmackException.NotConnectedException, XMPPException.XMPPErrorException,
            PubSubException.NotAPubSubNodeException {
        PubSubManager pm = PubSubManager.getInstance(connection, contact);

        LeafNode node = pm.getLeafNode(PEP_NODE_PUBLIC_KEY(v4_fingerprint));
        List<PayloadItem<PubkeyElement>> list = node.getItems(1);

        if (list.isEmpty()) {
            return null;
        }

        return list.get(0).getPayload();
    }

    /**
     * Publishes a {@link SecretkeyElement} to the secret key node.
     * The node will be configured to use the whitelist access model to prevent access from subscribers.
     *
     * @see <a href="https://xmpp.org/extensions/xep-0373.html#synchro-pep">
     *     XEP-0373 §5. Synchronizing the Secret Key with a Private PEP Node</a>
     *
     * @param connection {@link XMPPConnection} of the user
     * @param element a {@link SecretkeyElement} containing the encrypted secret key of the user
     * @throws InterruptedException if the connection gets interrupted.
     * @throws PubSubException.NotALeafNodeException if something is wrong with the PubSub node
     * @throws XMPPException.XMPPErrorException in case of an protocol related error
     * @throws SmackException.NotConnectedException if we are not connected
     * @throws SmackException.NoResponseException /watch?v=0peBq89ZTrc
     * @throws SmackException.NotLoggedInException if we are not logged in
     * @throws SmackException.FeatureNotSupportedException if the Server doesn't support the whitelist access model
     */
    public static void depositSecretKey(XMPPConnection connection, SecretkeyElement element)
            throws InterruptedException, PubSubException.NotALeafNodeException,
            XMPPException.XMPPErrorException, SmackException.NotConnectedException, SmackException.NoResponseException,
            SmackException.NotLoggedInException, SmackException.FeatureNotSupportedException {
        if (!OpenPgpManager.getInstanceFor(connection).serverSupportsSecretKeyBackups()) {
            throw new SmackException.FeatureNotSupportedException("http://jabber.org/protocol/pubsub#access-whitelist");
        }
        PubSubManager pm = PubSubManager.getInstance(connection);
        LeafNode secretKeyNode = pm.getOrCreateLeafNode(PEP_NODE_SECRET_KEY);
        PubSubDelegate.changeAccessModelIfNecessary(secretKeyNode, AccessModel.whitelist);

        secretKeyNode.publish(new PayloadItem<>(element));
    }

    /**
     * Fetch the latest {@link SecretkeyElement} from the private backup node.
     *
     * @see <a href="https://xmpp.org/extensions/xep-0373.html#synchro-pep">
     *      XEP-0373 §5. Synchronizing the Secret Key with a Private PEP Node</a>
     *
     * @param connection {@link XMPPConnection} of the user.
     * @return the secret key node or null, if it doesn't exist.
     * @throws InterruptedException if the connection gets interrupted
     * @throws PubSubException.NotALeafNodeException if there is an issue with the PubSub node
     * @throws XMPPException.XMPPErrorException if there is an XMPP protocol related issue
     * @throws SmackException.NotConnectedException if we are not connected
     * @throws SmackException.NoResponseException /watch?v=7U0FzQzJzyI
     */
    public static SecretkeyElement fetchSecretKey(XMPPConnection connection)
            throws InterruptedException, PubSubException.NotALeafNodeException, XMPPException.XMPPErrorException,
            SmackException.NotConnectedException, SmackException.NoResponseException {
        PubSubManager pm = PubSubManager.getInstance(connection);
        LeafNode secretKeyNode = pm.getOrCreateLeafNode(PEP_NODE_SECRET_KEY);
        List<PayloadItem<SecretkeyElement>> list = secretKeyNode.getItems(1);
        if (list.size() == 0) {
            LOGGER.log(Level.INFO, "No secret key published!");
            return null;
        }
        SecretkeyElement secretkeyElement = list.get(0).getPayload();
        return secretkeyElement;
    }

    /**
     * Delete the private backup node.
     *
     * @param connection {@link XMPPConnection} of the user.
     * @throws XMPPException.XMPPErrorException if there is an XMPP protocol related issue
     * @throws SmackException.NotConnectedException if we are not connected
     * @throws InterruptedException if the connection gets interrupted
     * @throws SmackException.NoResponseException if the server sends no response
     */
    public static void deleteSecretKeyNode(XMPPConnection connection)
            throws XMPPException.XMPPErrorException, SmackException.NotConnectedException, InterruptedException,
            SmackException.NoResponseException {
        PubSubManager pm = PubSubManager.getInstance(connection);
        pm.deleteNode(PEP_NODE_SECRET_KEY);
    }
}
