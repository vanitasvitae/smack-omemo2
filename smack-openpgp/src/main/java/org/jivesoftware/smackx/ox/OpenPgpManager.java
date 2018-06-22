/**
 *
 * Copyright 2017 Florian Schmaus, 2018 Paul Schaub.
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
package org.jivesoftware.smackx.ox;

import static org.jivesoftware.smackx.ox.util.PubSubDelegate.PEP_NODE_PUBLIC_KEYS;
import static org.jivesoftware.smackx.ox.util.PubSubDelegate.PEP_NODE_PUBLIC_KEYS_NOTIFY;
import static org.jivesoftware.smackx.ox.util.PubSubDelegate.fetchPubkey;
import static org.jivesoftware.smackx.ox.util.PubSubDelegate.publishPublicKey;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jivesoftware.smack.Manager;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.chat2.Chat;
import org.jivesoftware.smack.chat2.ChatManager;
import org.jivesoftware.smack.chat2.IncomingChatMessageListener;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.Async;
import org.jivesoftware.smack.util.stringencoder.Base64;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.ox.callback.AskForBackupCodeCallback;
import org.jivesoftware.smackx.ox.callback.DisplayBackupCodeCallback;
import org.jivesoftware.smackx.ox.callback.SecretKeyBackupSelectionCallback;
import org.jivesoftware.smackx.ox.callback.SecretKeyRestoreSelectionCallback;
import org.jivesoftware.smackx.ox.chat.OpenPgpContact;
import org.jivesoftware.smackx.ox.chat.OpenPgpFingerprints;
import org.jivesoftware.smackx.ox.element.CryptElement;
import org.jivesoftware.smackx.ox.element.OpenPgpContentElement;
import org.jivesoftware.smackx.ox.element.OpenPgpElement;
import org.jivesoftware.smackx.ox.element.PubkeyElement;
import org.jivesoftware.smackx.ox.element.PublicKeysListElement;
import org.jivesoftware.smackx.ox.element.SecretkeyElement;
import org.jivesoftware.smackx.ox.element.SignElement;
import org.jivesoftware.smackx.ox.element.SigncryptElement;
import org.jivesoftware.smackx.ox.exception.InvalidBackupCodeException;
import org.jivesoftware.smackx.ox.exception.MissingOpenPgpKeyPairException;
import org.jivesoftware.smackx.ox.exception.MissingOpenPgpPublicKeyException;
import org.jivesoftware.smackx.ox.exception.MissingUserIdOnKeyException;
import org.jivesoftware.smackx.ox.exception.NoBackupFoundException;
import org.jivesoftware.smackx.ox.exception.SmackOpenPgpException;
import org.jivesoftware.smackx.ox.listener.internal.CryptElementReceivedListener;
import org.jivesoftware.smackx.ox.listener.internal.SignElementReceivedListener;
import org.jivesoftware.smackx.ox.listener.internal.SigncryptElementReceivedListener;
import org.jivesoftware.smackx.ox.util.KeyBytesAndFingerprint;
import org.jivesoftware.smackx.ox.util.PubSubDelegate;
import org.jivesoftware.smackx.pep.PEPListener;
import org.jivesoftware.smackx.pep.PEPManager;
import org.jivesoftware.smackx.pubsub.EventElement;
import org.jivesoftware.smackx.pubsub.ItemsExtension;
import org.jivesoftware.smackx.pubsub.LeafNode;
import org.jivesoftware.smackx.pubsub.PayloadItem;
import org.jivesoftware.smackx.pubsub.PubSubException;
import org.jivesoftware.smackx.pubsub.PubSubManager;

import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.EntityBareJid;
import org.xmlpull.v1.XmlPullParserException;

public final class OpenPgpManager extends Manager {

    private static final Logger LOGGER = Logger.getLogger(OpenPgpManager.class.getName());

    /**
     * Map of instances.
     */
    private static final Map<XMPPConnection, OpenPgpManager> INSTANCES = new WeakHashMap<>();

    /**
     * {@link OpenPgpProvider} responsible for processing keys, encrypting and decrypting messages and so on.
     */
    private OpenPgpProvider provider;

    private final Map<BareJid, OpenPgpContact> openPgpCapableContacts = new HashMap<>();

    private final Set<SigncryptElementReceivedListener> signcryptElementReceivedListeners = new HashSet<>();
    private final Set<SignElementReceivedListener> signElementReceivedListeners = new HashSet<>();
    private final Set<CryptElementReceivedListener> cryptElementReceivedListeners = new HashSet<>();

    /**
     * Private constructor to avoid instantiation without putting the object into {@code INSTANCES}.
     *
     * @param connection xmpp connection.
     */
    private OpenPgpManager(XMPPConnection connection) {
        super(connection);
        ChatManager.getInstanceFor(connection).addIncomingListener(incomingOpenPgpMessageListener);
    }

    /**
     * Get the instance of the {@link OpenPgpManager} which belongs to the {@code connection}.
     *
     * @param connection xmpp connection.
     * @return instance of the manager.
     */
    public static OpenPgpManager getInstanceFor(XMPPConnection connection) {
        OpenPgpManager manager = INSTANCES.get(connection);
        if (manager == null) {
            manager = new OpenPgpManager(connection);
            INSTANCES.put(connection, manager);
        }
        return manager;
    }

    /**
     * Set the {@link OpenPgpProvider} which will be used to process incoming OpenPGP elements,
     * as well as to execute cryptographic operations.
     *
     * @param provider OpenPgpProvider.
     */
    public void setOpenPgpProvider(OpenPgpProvider provider) {
        this.provider = provider;
    }

    /**
     * Return the registered {@link OpenPgpProvider}.
     *
     * @return provider.
     */
    OpenPgpProvider getOpenPgpProvider() {
        return provider;
    }

    /**
     * Generate a fresh OpenPGP key pair, given we don't have one already.
     * Publish the public key to the Public Key Node and update the Public Key Metadata Node with our keys fingerprint.
     * Lastly register a {@link PEPListener} which listens for updates to Public Key Metadata Nodes.
     *
     * @throws NoSuchAlgorithmException if we are missing an algorithm to generate a fresh key pair.
     * @throws NoSuchProviderException if we are missing a suitable {@link java.security.Provider}.
     * @throws SmackOpenPgpException if something bad happens during key generation/loading.
     * @throws InterruptedException
     * @throws PubSubException.NotALeafNodeException
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws SmackException.NoResponseException
     */
    public void announceSupportAndPublish()
            throws NoSuchAlgorithmException, NoSuchProviderException, SmackOpenPgpException,
            InterruptedException, PubSubException.NotALeafNodeException, XMPPException.XMPPErrorException,
            SmackException.NotConnectedException, SmackException.NoResponseException, IOException,
            InvalidAlgorithmParameterException, SmackException.NotLoggedInException {
        throwIfNoProviderSet();
        throwIfNotAuthenticated();

        BareJid ourJid = connection().getUser().asBareJid();

        OpenPgpV4Fingerprint primaryFingerprint = getOurFingerprint();

        if (primaryFingerprint == null) {
            primaryFingerprint = generateAndImportKeyPair(ourJid);
        }

        // Create <pubkey/> element
        PubkeyElement pubkeyElement;
        try {
            pubkeyElement = createPubkeyElement(ourJid, primaryFingerprint, new Date());
        } catch (MissingOpenPgpPublicKeyException e) {
            throw new AssertionError("Cannot publish our public key, since it is missing (MUST NOT happen!)");
        }

        // publish it
        publishPublicKey(connection(), pubkeyElement, primaryFingerprint);

        // Subscribe to public key changes
        PEPManager.getInstanceFor(connection()).addPEPListener(metadataListener);
        ServiceDiscoveryManager.getInstanceFor(connection())
                .addFeature(PEP_NODE_PUBLIC_KEYS_NOTIFY);
    }

    public OpenPgpV4Fingerprint generateAndImportKeyPair(BareJid ourJid)
            throws NoSuchAlgorithmException, IOException, InvalidAlgorithmParameterException, NoSuchProviderException,
            SmackOpenPgpException {
        KeyBytesAndFingerprint bytesAndFingerprint = provider.generateOpenPgpKeyPair(ourJid);
        OpenPgpV4Fingerprint fingerprint = bytesAndFingerprint.getFingerprint();

        // This should never throw, since we set our jid literally one line above this comment.
        try {
            provider.importSecretKey(ourJid, bytesAndFingerprint.getBytes());
        } catch (MissingUserIdOnKeyException e) {
            throw new AssertionError(e);
        }

        return fingerprint;
    }

    /**
     * Return the upper-case hex encoded OpenPGP v4 fingerprint of our key pair.
     *
     * @return fingerprint.
     */
    public OpenPgpV4Fingerprint getOurFingerprint() {
        throwIfNoProviderSet();
        return provider.getStore().getPrimaryOpenPgpKeyPairFingerprint();
    }

    /**
     * Return an OpenPGP capable contact.
     * This object can be used as an entry point to OpenPGP related API.
     *
     * @param jid {@link BareJid} of the contact.
     * @return {@link OpenPgpContact}.
     * @throws SmackOpenPgpException if something happens while gathering fingerprints.
     * @throws InterruptedException
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws SmackException.NoResponseException
     */
    public OpenPgpContact getOpenPgpContact(EntityBareJid jid)
            throws SmackOpenPgpException, InterruptedException, XMPPException.XMPPErrorException,
            SmackException.NotConnectedException, SmackException.NoResponseException,
            SmackException.NotLoggedInException {
        throwIfNotAuthenticated();

        OpenPgpContact openPgpContact = openPgpCapableContacts.get(jid);

        if (openPgpContact == null) {
            OpenPgpFingerprints theirKeys = determineContactsKeys(jid);
            OpenPgpFingerprints ourKeys = determineContactsKeys(connection().getUser().asBareJid());
            openPgpContact = new OpenPgpContact(getOpenPgpProvider(), jid, ourKeys, theirKeys);
            openPgpCapableContacts.put(jid, openPgpContact);
        }

        return openPgpContact;
    }


    /**
     * Determine, if we can sync secret keys using private PEP nodes as described in the XEP.
     * Requirements on the server side are support for PEP and support for the whitelist access model of PubSub.
     *
     * @see <a href="https://xmpp.org/extensions/xep-0373.html#synchro-pep">XEP-0373 §5</a>
     *
     * @return true, if the server supports secret key backups, otherwise false.
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws InterruptedException
     * @throws SmackException.NoResponseException
     */
    public boolean serverSupportsSecretKeyBackups()
            throws XMPPException.XMPPErrorException, SmackException.NotConnectedException, InterruptedException,
            SmackException.NoResponseException, SmackException.NotLoggedInException {
        throwIfNotAuthenticated();
        boolean pep = PEPManager.getInstanceFor(connection()).isSupported();
        boolean whitelist = PubSubManager.getInstance(connection(), connection().getUser().asBareJid())
                .getSupportedFeatures().containsFeature("http://jabber.org/protocol/pubsub#access-whitelist");
        return pep && whitelist;
    }

    /**
     * Upload the encrypted secret key to a private PEP node.
     *
     * @see <a href="https://xmpp.org/extensions/xep-0373.html#synchro-pep">XEP-0373 §5</a>
     *
     * @param displayCodeCallback callback, which will receive the backup password used to encrypt the secret key.
     * @param selectKeyCallback callback, which will receive the users choice of which keys will be backed up.
     * @throws InterruptedException
     * @throws PubSubException.NotALeafNodeException
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws SmackException.NoResponseException
     */
    public void backupSecretKeyToServer(DisplayBackupCodeCallback displayCodeCallback,
                                        SecretKeyBackupSelectionCallback selectKeyCallback)
            throws InterruptedException, PubSubException.NotALeafNodeException,
            XMPPException.XMPPErrorException, SmackException.NotConnectedException, SmackException.NoResponseException,
            SmackException.NotLoggedInException, SmackOpenPgpException, IOException {
        throwIfNoProviderSet();
        throwIfNotAuthenticated();

        BareJid ownJid = connection().getUser().asBareJid();

        String backupCode = SecretKeyBackupHelper.generateBackupPassword();
        Set<OpenPgpV4Fingerprint> availableKeyPairs = provider.getStore().getAvailableKeyPairFingerprints(ownJid);
        Set<OpenPgpV4Fingerprint> selectedKeyPairs = selectKeyCallback.selectKeysToBackup(availableKeyPairs);

        SecretkeyElement secretKey = SecretKeyBackupHelper.createSecretkeyElement(provider, ownJid, selectedKeyPairs, backupCode);

        PubSubDelegate.depositSecretKey(connection(), secretKey);
        displayCodeCallback.displayBackupCode(backupCode);
    }

    /**
     * Delete the private {@link LeafNode} containing our secret key backup.
     *
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws InterruptedException
     * @throws SmackException.NoResponseException
     */
    public void deleteSecretKeyServerBackup()
            throws XMPPException.XMPPErrorException, SmackException.NotConnectedException, InterruptedException,
            SmackException.NoResponseException, SmackException.NotLoggedInException {
        throwIfNotAuthenticated();
        PubSubDelegate.deleteSecretKeyNode(connection());
    }

    /**
     * Fetch a secret key backup from the server and try to restore a selected secret key from it.
     *
     * @param codeCallback callback for prompting the user to provide the secret backup code.
     * @param selectionCallback callback allowing the user to select a secret key which will be restored.
     * @throws InterruptedException
     * @throws PubSubException.NotALeafNodeException
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws SmackException.NoResponseException
     * @throws SmackOpenPgpException if something goes wrong while restoring the secret key.
     * @throws InvalidBackupCodeException if the user-provided backup code is invalid.
     */
    public void restoreSecretKeyServerBackup(AskForBackupCodeCallback codeCallback,
                                             SecretKeyRestoreSelectionCallback selectionCallback)
            throws InterruptedException, PubSubException.NotALeafNodeException, XMPPException.XMPPErrorException,
            SmackException.NotConnectedException, SmackException.NoResponseException, SmackOpenPgpException,
            InvalidBackupCodeException, SmackException.NotLoggedInException, IOException, MissingUserIdOnKeyException,
            NoBackupFoundException {
        throwIfNoProviderSet();
        throwIfNotAuthenticated();
        SecretkeyElement backup = PubSubDelegate.fetchSecretKey(connection());
        if (backup == null) {
            throw new NoBackupFoundException();
        }

        String backupCode = codeCallback.askForBackupCode();

        OpenPgpV4Fingerprint fingerprint = SecretKeyBackupHelper.restoreSecretKeyBackup(provider, backup, backupCode);
        provider.getStore().setPrimaryOpenPgpKeyPairFingerprint(fingerprint);
    }

    /**
     * Determine which keys belong to a user and fetch any missing keys.
     *
     * @param jid {@link BareJid} of the user in question.
     * @return {@link OpenPgpFingerprints} object containing the announced, available and unfetchable keys of the user.
     * @throws SmackOpenPgpException
     * @throws InterruptedException
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws SmackException.NoResponseException
     */
    private OpenPgpFingerprints determineContactsKeys(BareJid jid)
            throws SmackOpenPgpException, InterruptedException, XMPPException.XMPPErrorException,
            SmackException.NotConnectedException, SmackException.NoResponseException {
        Set<OpenPgpV4Fingerprint> announced = provider.getStore().getAnnouncedKeysFingerprints(jid).keySet();
        Set<OpenPgpV4Fingerprint> available = provider.getStore().getAvailableKeysFingerprints(jid).keySet();
        Map<OpenPgpV4Fingerprint, Throwable> unfetched = new HashMap<>();
        for (OpenPgpV4Fingerprint f : announced) {
            if (!available.contains(f)) {
                try {
                    PubkeyElement pubkeyElement = PubSubDelegate.fetchPubkey(connection(), jid, f);
                    if (pubkeyElement == null) {
                        continue;
                    }

                    processPublicKey(pubkeyElement, jid);
                    available.add(f);

                } catch (PubSubException.NotAPubSubNodeException | PubSubException.NotALeafNodeException e) {
                    LOGGER.log(Level.WARNING, "Could not fetch public key " + f.toString() + " of user " + jid.toString(), e);
                    unfetched.put(f, e);
                } catch (MissingUserIdOnKeyException e) {
                    LOGGER.log(Level.WARNING, "Key does not contain user-id of " + jid + ". Ignoring the key.", e);
                    unfetched.put(f, e);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not import key " + f.toString() + " of user " + jid.toString(), e);
                }
            }
        }
        return new OpenPgpFingerprints(jid, announced, available, unfetched);
    }

    /**
     * {@link PEPListener} that listens for changes to the OX public keys metadata node.
     *
     * @see <a href="https://xmpp.org/extensions/xep-0373.html#pubsub-notifications">XEP-0373 §4.4</a>
     */
    private final PEPListener metadataListener = new PEPListener() {
        @Override
        public void eventReceived(final EntityBareJid from, final EventElement event, final Message message) {
            if (PEP_NODE_PUBLIC_KEYS.equals(event.getEvent().getNode())) {
                final BareJid contact = from.asBareJid();
                LOGGER.log(Level.INFO, "Received OpenPGP metadata update from " + contact);
                Async.go(new Runnable() {
                    @Override
                    public void run() {
                        ItemsExtension items = (ItemsExtension) event.getExtensions().get(0);
                        PayloadItem<?> payload = (PayloadItem) items.getItems().get(0);
                        PublicKeysListElement listElement = (PublicKeysListElement) payload.getPayload();

                        processPublicKeysListElement(from, listElement);
                    }
                }, "ProcessOXMetadata");
            }
        }
    };

    public void requestMetadataUpdate(BareJid contact)
            throws InterruptedException, PubSubException.NotALeafNodeException, SmackException.NoResponseException,
            SmackException.NotConnectedException, XMPPException.XMPPErrorException,
            PubSubException.NotAPubSubNodeException {
        PublicKeysListElement metadata = PubSubDelegate.fetchPubkeysList(connection(), contact);
        processPublicKeysListElement(contact, metadata);
    }

    private void processPublicKeysListElement(BareJid contact, PublicKeysListElement listElement) {
        Map<OpenPgpV4Fingerprint, Date> announcedKeys = new HashMap<>();
        for (OpenPgpV4Fingerprint f : listElement.getMetadata().keySet()) {
            PublicKeysListElement.PubkeyMetadataElement meta = listElement.getMetadata().get(f);
            announcedKeys.put(meta.getV4Fingerprint(), meta.getDate());
        }

        provider.getStore().setAnnouncedKeysFingerprints(contact, announcedKeys);

        Set<OpenPgpV4Fingerprint> missingKeys = listElement.getMetadata().keySet();
        try {
            missingKeys.removeAll(provider.getStore().getAvailableKeysFingerprints(contact).keySet());
            for (OpenPgpV4Fingerprint missing : missingKeys) {
                try {
                    PubkeyElement pubkeyElement = fetchPubkey(connection(), contact, missing);
                    processPublicKey(pubkeyElement, contact);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error fetching missing OpenPGP key " + missing.toString(), e);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing OpenPGP metadata update from " + contact + ".", e);
        }
    }

    private final IncomingChatMessageListener incomingOpenPgpMessageListener =
            new IncomingChatMessageListener() {
                @Override
                public void newIncomingMessage(EntityBareJid from, Message message, Chat chat) {
                    OpenPgpElement element = message.getExtension(OpenPgpElement.ELEMENT, OpenPgpElement.NAMESPACE);
                    if (element == null) {
                        // Message does not contain an OpenPgpElement -> discard
                        return;
                    }

                    OpenPgpContact contact;
                    try {
                        contact = getOpenPgpContact(from);
                    } catch (SmackOpenPgpException | InterruptedException | XMPPException.XMPPErrorException |
                            SmackException.NotLoggedInException | SmackException.NotConnectedException |
                            SmackException.NoResponseException e) {
                        LOGGER.log(Level.WARNING, "Could not begin encrypted chat with " + from, e);
                        return;
                    }

                    OpenPgpContentElement contentElement = null;
                    try {
                        contentElement = contact.receive(element);
                    } catch (SmackOpenPgpException e) {
                        LOGGER.log(Level.WARNING, "Could not decrypt incoming OpenPGP encrypted message", e);
                    } catch (XmlPullParserException | IOException e) {
                        LOGGER.log(Level.WARNING, "Invalid XML content of incoming OpenPGP encrypted message", e);
                    } catch (MissingOpenPgpKeyPairException e) {
                        LOGGER.log(Level.WARNING, "Could not decrypt incoming OpenPGP encrypted message due to missing secret key", e);
                    }

                    if (contentElement instanceof SigncryptElement) {
                        for (SigncryptElementReceivedListener l : signcryptElementReceivedListeners) {
                            l.signcryptElementReceived(contact, message, (SigncryptElement) contentElement);
                        }
                        return;
                    }

                    if (contentElement instanceof SignElement) {
                        for (SignElementReceivedListener l : signElementReceivedListeners) {
                            l.signElementReceived(contact, message, (SignElement) contentElement);
                        }
                        return;
                    }

                    if (contentElement instanceof CryptElement) {
                        for (CryptElementReceivedListener l : cryptElementReceivedListeners) {
                            l.cryptElementReceived(contact, message, (CryptElement) contentElement);
                        }
                        return;
                    }
                }
            };

    /*
    Private stuff.
     */

    private void processPublicKey(PubkeyElement pubkeyElement, BareJid owner)
            throws MissingUserIdOnKeyException, IOException, SmackOpenPgpException {
        byte[] base64 = pubkeyElement.getDataElement().getB64Data();
        provider.importPublicKey(owner, Base64.decode(base64));
    }

    private PubkeyElement createPubkeyElement(BareJid owner,
                                              OpenPgpV4Fingerprint fingerprint,
                                              Date date)
            throws MissingOpenPgpPublicKeyException {
        byte[] keyBytes = provider.getStore().getPublicKeyRingBytes(owner, fingerprint);
        return createPubkeyElement(keyBytes, date);
    }

    private static PubkeyElement createPubkeyElement(byte[] bytes, Date date) {
        return new PubkeyElement(new PubkeyElement.PubkeyDataElement(Base64.encode(bytes)), date);
    }

    void addSigncryptReceivedListener(SigncryptElementReceivedListener listener) {
        signcryptElementReceivedListeners.add(listener);
    }

    void removeSigncryptElementReceivedListener(SigncryptElementReceivedListener listener) {
        signcryptElementReceivedListeners.remove(listener);
    }

    void addSignElementReceivedListener(SignElementReceivedListener listener) {
        signElementReceivedListeners.add(listener);
    }

    void removeSignElementReceivedListener(SignElementReceivedListener listener) {
        signElementReceivedListeners.remove(listener);
    }

    void addCryptElementReceivedListener(CryptElementReceivedListener listener) {
        cryptElementReceivedListeners.add(listener);
    }

    void removeCryptElementReceivedListener(CryptElementReceivedListener listener) {
        cryptElementReceivedListeners.remove(listener);
    }

    /**
     * Throw an {@link IllegalStateException} if no {@link OpenPgpProvider} is set.
     * The OpenPgpProvider is used to process information related to RFC-4880.
     */
    private void throwIfNoProviderSet() {
        if (provider == null) {
            throw new IllegalStateException("No OpenPgpProvider set!");
        }
    }

    private void throwIfNotAuthenticated() throws SmackException.NotLoggedInException {
        if (!connection().isAuthenticated()) {
            throw new SmackException.NotLoggedInException();
        }
    }
}
