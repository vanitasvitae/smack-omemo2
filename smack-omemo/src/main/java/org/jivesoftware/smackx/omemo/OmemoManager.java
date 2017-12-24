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
package org.jivesoftware.smackx.omemo;

import static org.jivesoftware.smackx.omemo.util.OmemoConstants.BODY_OMEMO_HINT;
import static org.jivesoftware.smackx.omemo.util.OmemoConstants.OMEMO_NAMESPACE_V_AXOLOTL;
import static org.jivesoftware.smackx.omemo.util.OmemoConstants.PEP_NODE_DEVICE_LIST_NOTIFY;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jivesoftware.smack.AbstractConnectionListener;
import org.jivesoftware.smack.Manager;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.StanzaFilter;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.NamedElement;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.util.Async;
import org.jivesoftware.smackx.carbons.CarbonCopyReceivedListener;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.carbons.packet.CarbonExtension;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.eme.element.ExplicitMessageEncryptionElement;
import org.jivesoftware.smackx.hints.element.StoreHint;
import org.jivesoftware.smackx.mam.MamManager;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.muc.RoomInfo;
import org.jivesoftware.smackx.omemo.element.OmemoDeviceListElement_VAxolotl;
import org.jivesoftware.smackx.omemo.element.OmemoElement;
import org.jivesoftware.smackx.omemo.exceptions.CannotEstablishOmemoSessionException;
import org.jivesoftware.smackx.omemo.exceptions.CorruptedOmemoKeyException;
import org.jivesoftware.smackx.omemo.exceptions.CryptoFailedException;
import org.jivesoftware.smackx.omemo.exceptions.NoOmemoSupportException;
import org.jivesoftware.smackx.omemo.exceptions.NoRawSessionException;
import org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException;
import org.jivesoftware.smackx.omemo.internal.CachedDeviceList;
import org.jivesoftware.smackx.omemo.internal.CipherAndAuthTag;
import org.jivesoftware.smackx.omemo.internal.ClearTextMessage;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.internal.OmemoMessageInformation;
import org.jivesoftware.smackx.omemo.listener.OmemoMessageListener;
import org.jivesoftware.smackx.omemo.listener.OmemoMucMessageListener;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jivesoftware.smackx.omemo.trust.TrustCallback;
import org.jivesoftware.smackx.omemo.trust.TrustState;
import org.jivesoftware.smackx.pep.PEPListener;
import org.jivesoftware.smackx.pep.PEPManager;
import org.jivesoftware.smackx.pubsub.EventElement;
import org.jivesoftware.smackx.pubsub.ItemsExtension;
import org.jivesoftware.smackx.pubsub.PayloadItem;
import org.jivesoftware.smackx.pubsub.PubSubException;
import org.jivesoftware.smackx.pubsub.packet.PubSub;

import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.DomainBareJid;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.EntityFullJid;

/**
 * Manager that allows sending messages encrypted with OMEMO.
 * This class also provides some methods useful for a client that implements OMEMO.
 *
 * @author Paul Schaub
 */

public final class OmemoManager extends Manager {
    private static final Logger LOGGER = Logger.getLogger(OmemoManager.class.getName());

    private static final Integer UNKNOWN_DEVICE_ID = -1;
    private final Object LOCK = new Object();

    private static final WeakHashMap<XMPPConnection, TreeMap<Integer,OmemoManager>> INSTANCES = new WeakHashMap<>();
    private final OmemoService<?, ?, ?, ?, ?, ?, ?, ?, ?> service;

    private final HashSet<OmemoMessageListener> omemoMessageListeners = new HashSet<>();
    private final HashSet<OmemoMucMessageListener> omemoMucMessageListeners = new HashSet<>();

    private TrustCallback trustCallback;

    private BareJid ownJid;
    private Integer deviceId;

    /**
     * Private constructor.
     *
     * @param connection connection
     * @param deviceId deviceId
     */
    private OmemoManager(XMPPConnection connection, Integer deviceId) {
        super(connection);

        service = OmemoService.getInstance();

        this.deviceId = deviceId;

        if (connection.isAuthenticated()) {
            initBareJidAndDeviceId(this);
        } else {
            connection.addConnectionListener(new AbstractConnectionListener() {
                @Override
                public void authenticated(XMPPConnection connection, boolean resumed) {
                    initBareJidAndDeviceId(OmemoManager.this);
                }
            });
        }

        service.registerManager(this);

        // StanzaListeners
        startStanzaListeners();

        // Announce OMEMO support
        ServiceDiscoveryManager.getInstanceFor(connection).addFeature(PEP_NODE_DEVICE_LIST_NOTIFY);
    }

    /**
     * Return an OmemoManager instance for the given connection and deviceId.
     * If there was an OmemoManager for the connection and id before, return it. Otherwise create a new OmemoManager
     * instance and return it.
     *
     * @param connection XmppConnection.
     * @param deviceId MUST NOT be null and MUST be greater than 0.
     *
     * @return manager
     */
    public synchronized static OmemoManager getInstanceFor(XMPPConnection connection, Integer deviceId) {
        if (deviceId == null || deviceId < 1) {
            throw new IllegalArgumentException("DeviceId MUST NOT be null and MUST be greater than 0.");
        }

        TreeMap<Integer,OmemoManager> managersOfConnection = INSTANCES.get(connection);
        if (managersOfConnection == null) {
            managersOfConnection = new TreeMap<>();
            INSTANCES.put(connection, managersOfConnection);
        }

        OmemoManager manager = managersOfConnection.get(deviceId);
        if (manager == null) {
            manager = new OmemoManager(connection, deviceId);
            managersOfConnection.put(deviceId, manager);
        }

        return manager;
    }

    /**
     * Returns an OmemoManager instance for the given connection. If there was one manager for the connection before,
     * return it. If there were multiple managers before, return the one with the lowest deviceId.
     * If there was no manager before, return a new one. As soon as the connection gets authenticated, the manager
     * will look for local deviceIDs and select the lowest one as its id. If there are not local deviceIds, the manager
     * will assign itself a random id.
     *
     * @param connection XmppConnection.
     *
     * @return manager
     */
    public synchronized static OmemoManager getInstanceFor(XMPPConnection connection) {
        TreeMap<Integer, OmemoManager> managers = INSTANCES.get(connection);
        if (managers == null) {
            managers = new TreeMap<>();
            INSTANCES.put(connection, managers);
        }

        OmemoManager manager;
        if (managers.size() == 0) {

            manager = new OmemoManager(connection, UNKNOWN_DEVICE_ID);
            managers.put(UNKNOWN_DEVICE_ID, manager);

        } else {
            manager = managers.get(managers.firstKey());
        }

        return manager;
    }

    /**
     * Set a TrustCallback for this particular OmemoManager.
     * TrustCallbacks are used to query and modify trust decisions.
     *
     * @param callback trustCallback.
     */
    public void setTrustCallback(TrustCallback callback) {
        if (trustCallback != null) {
            throw new IllegalStateException("TrustCallback can only be set once.");
        }
        trustCallback = callback;
    }

    /**
     * Initializes the OmemoManager. This method must be called before the manager can be used.
     *
     * @throws CorruptedOmemoKeyException
     * @throws InterruptedException
     * @throws SmackException.NoResponseException
     * @throws SmackException.NotConnectedException
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotLoggedInException
     * @throws PubSubException.NotALeafNodeException
     */
    public void initialize()
            throws SmackException.NotLoggedInException, CorruptedOmemoKeyException, InterruptedException,
            SmackException.NoResponseException, SmackException.NotConnectedException, XMPPException.XMPPErrorException,
            PubSubException.NotALeafNodeException {
        synchronized (LOCK) {
            if (!connection().isAuthenticated()) {
                throw new SmackException.NotLoggedInException();
            }

            getOmemoService().publish(new LoggedInOmemoManager(this));
        }
    }

    /**
     * Initialize the manager without blocking. Once the manager is successfully initialized, the finishedCallback will
     * be notified. It will also get notified, if an error occurs.
     *
     * @param finishedCallback callback that gets called once the manager is initialized.
     */
    public void initializeAsync(final FinishedCallback finishedCallback) {
        Async.go(new Runnable() {
            @Override
            public void run() {
                try {
                    initialize();
                    finishedCallback.initializationFinished(OmemoManager.this);
                } catch (Exception e) {
                    finishedCallback.initializationFailed(e);
                }
            }
        });
    }

    /**
     * OMEMO encrypt a cleartext message for a single recipient.
     * Note that this method does NOT set the 'to' attribute of the message.
     *
     * @param to recipients bareJid
     * @param message text to encrypt
     * @return encrypted message
     * @throws CryptoFailedException                when something crypto related fails
     * @throws UndecidedOmemoIdentityException      When there are undecided devices
     * @throws NoSuchAlgorithmException
     * @throws InterruptedException
     * @throws CannotEstablishOmemoSessionException when we could not create session withs all of the recipients
     *                                              devices.
     * @throws SmackException.NotConnectedException
     * @throws SmackException.NoResponseException
     */
    public Message encrypt(BareJid to, String message)
            throws CryptoFailedException, UndecidedOmemoIdentityException, NoSuchAlgorithmException,
            InterruptedException, CannotEstablishOmemoSessionException, SmackException.NotConnectedException,
            SmackException.NoResponseException, SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            LoggedInOmemoManager guard = new LoggedInOmemoManager(this);
            Message plaintext = new Message();
            plaintext.setBody(message);
            OmemoElement encrypted = getOmemoService().processSendingMessage(guard, to, plaintext);
            return finishMessage(encrypted);
        }
    }

    /**
     * OMEMO encrypt a cleartext message for multiple recipients.
     *
     * @param recipients recipients barejids
     * @param message text to encrypt
     * @return encrypted message.
     * @throws CryptoFailedException    When something crypto related fails
     * @throws UndecidedOmemoIdentityException  When there are undecided devices.
     * @throws NoSuchAlgorithmException
     * @throws InterruptedException
     * @throws CannotEstablishOmemoSessionException When there is one recipient, for whom we failed to create a session
     *                                              with every one of their devices.
     * @throws SmackException.NotConnectedException
     * @throws SmackException.NoResponseException
     */
    public Message encrypt(ArrayList<BareJid> recipients, String message)
            throws CryptoFailedException, UndecidedOmemoIdentityException, NoSuchAlgorithmException,
            InterruptedException, CannotEstablishOmemoSessionException, SmackException.NotConnectedException,
            SmackException.NoResponseException, SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            Message m = new Message();
            m.setBody(message);
            OmemoElement encrypted = getOmemoService().processSendingMessage(
                    new LoggedInOmemoManager(this), recipients, m);
            return finishMessage(encrypted);
        }
    }

    /**
     * Encrypt a message for all recipients in the MultiUserChat.
     *
     * @param muc multiUserChat
     * @param message message to send
     * @return encrypted message
     * @throws UndecidedOmemoIdentityException when there are undecided devices.
     * @throws NoSuchAlgorithmException
     * @throws CryptoFailedException
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws InterruptedException
     * @throws SmackException.NoResponseException
     * @throws NoOmemoSupportException When the muc doesn't support OMEMO.
     * @throws CannotEstablishOmemoSessionException when there is a user for whom we could not create a session
     *                                              with any of their devices.
     */
    public Message encrypt(MultiUserChat muc, String message)
            throws UndecidedOmemoIdentityException, NoSuchAlgorithmException, CryptoFailedException,
            XMPPException.XMPPErrorException, SmackException.NotConnectedException, InterruptedException,
            SmackException.NoResponseException, NoOmemoSupportException, CannotEstablishOmemoSessionException,
            SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            if (!multiUserChatSupportsOmemo(muc.getRoom())) {
                throw new NoOmemoSupportException();
            }

            Message m = new Message();
            m.setBody(message);
            ArrayList<BareJid> recipients = new ArrayList<>();

            for (EntityFullJid e : muc.getOccupants()) {
                recipients.add(muc.getOccupant(e).getJid().asBareJid());
            }
            return encrypt(recipients, message);
        }
    }

    /**
     * Encrypt a message for all users we could build a session with successfully in a previous attempt.
     * This method can come in handy as a fallback when encrypting a message fails due to devices we cannot
     * build a session with.
     *
     * @param exception CannotEstablishSessionException from a previous encrypt(user(s), message) call.
     * @param message message we want to send.
     * @return encrypted message
     * @throws CryptoFailedException
     * @throws UndecidedOmemoIdentityException when there are undecided identities.
     */
    public Message encryptForExistingSessions(CannotEstablishOmemoSessionException exception, String message)
            throws CryptoFailedException, UndecidedOmemoIdentityException, SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            Message m = new Message();
            m.setBody(message);
            OmemoElement encrypted = getOmemoService()
                    .encryptOmemoMessage(new LoggedInOmemoManager(this), exception.getSuccesses(), m);
            return finishMessage(encrypted);
        }
    }

    /**
     * Decrypt an OMEMO message. This method comes handy when dealing with messages that were not automatically
     * decrypted by smack-omemo, eg. MAM query messages.
     * @param sender sender of the message
     * @param omemoMessage message
     * @return decrypted message
     * @throws InterruptedException                 Exception
     * @throws SmackException.NoResponseException   Exception
     * @throws SmackException.NotConnectedException Exception
     * @throws CryptoFailedException                When decryption fails
     * @throws XMPPException.XMPPErrorException     Exception
     * @throws CorruptedOmemoKeyException           When the used keys are invalid
     * @throws NoRawSessionException                When there is no double ratchet session found for this message
     */
    public ClearTextMessage decrypt(BareJid sender, Message omemoMessage)
            throws InterruptedException, SmackException.NoResponseException, SmackException.NotConnectedException,
            CryptoFailedException, XMPPException.XMPPErrorException, CorruptedOmemoKeyException, NoRawSessionException,
            SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            return getOmemoService().processLocalMessage(new LoggedInOmemoManager(this), sender, omemoMessage);
        }
    }

    /**
     * Return a list of all OMEMO messages that were found in the MAM query result, that could be successfully
     * decrypted. Normal cleartext messages are also added to this list.
     *
     * @param mamQueryResult mamQueryResult
     * @return list of decrypted OmemoMessages
     * @throws InterruptedException                 Exception
     * @throws XMPPException.XMPPErrorException     Exception
     * @throws SmackException.NotConnectedException Exception
     * @throws SmackException.NoResponseException   Exception
     */
    public List<ClearTextMessage> decryptMamQueryResult(MamManager.MamQueryResult mamQueryResult)
            throws InterruptedException, XMPPException.XMPPErrorException, SmackException.NotConnectedException,
            SmackException.NoResponseException, SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            List<ClearTextMessage> l = new ArrayList<>();
            l.addAll(getOmemoService().decryptMamQueryResult(new LoggedInOmemoManager(this), mamQueryResult));
            return l;
        }
    }

    /**
     * Trust that a fingerprint belongs to an OmemoDevice.
     * The fingerprint must be the lowercase, hexadecimal fingerprint of the identityKey of the device and must
     * be of length 64.
     * @param device device
     * @param fingerprint fingerprint
     */
    public void trustOmemoIdentity(OmemoDevice device, OmemoFingerprint fingerprint) {
        if (trustCallback == null) {
            throw new IllegalStateException("No TrustCallback set.");
        }

        trustCallback.setTrust(device, fingerprint, TrustState.trusted);
    }

    /**
     * Distrust the fingerprint/OmemoDevice tuple.
     * The fingerprint must be the lowercase, hexadecimal fingerprint of the identityKey of the device and must
     * be of length 64.
     * @param device device
     * @param fingerprint fingerprint
     */
    public void distrustOmemoIdentity(OmemoDevice device, OmemoFingerprint fingerprint) {
        if (trustCallback == null) {
            throw new IllegalStateException("No TrustCallback set.");
        }

        trustCallback.setTrust(device, fingerprint, TrustState.untrusted);
    }

    /**
     * Returns true, if the fingerprint/OmemoDevice tuple is trusted, otherwise false.
     * The fingerprint must be the lowercase, hexadecimal fingerprint of the identityKey of the device and must
     * be of length 64.
     * @param device device
     * @param fingerprint fingerprint
     * @return
     */
    public boolean isTrustedOmemoIdentity(OmemoDevice device, OmemoFingerprint fingerprint) {
        if (trustCallback == null) {
            throw new IllegalStateException("No TrustCallback set.");
        }

        return trustCallback.getTrust(device, fingerprint) == TrustState.trusted;
    }

    /**
     * Returns true, if the fingerprint/OmemoDevice tuple is decided by the user.
     * The fingerprint must be the lowercase, hexadecimal fingerprint of the identityKey of the device and must
     * be of length 64.
     * @param device device
     * @param fingerprint fingerprint
     * @return
     */
    public boolean isDecidedOmemoIdentity(OmemoDevice device, OmemoFingerprint fingerprint) {
        if (trustCallback == null) {
            throw new IllegalStateException("No TrustCallback set.");
        }

        return trustCallback.getTrust(device, fingerprint) != TrustState.undecided;
    }

    /**
     * Send a ratchet update message. This can be used to advance the ratchet of a session in order to maintain forward
     * secrecy.
     *
     * @param recipient recipient
     * @throws UndecidedOmemoIdentityException      When the trust of session with the recipient is not decided yet
     * @throws CorruptedOmemoKeyException           When the used identityKeys are corrupted
     * @throws CryptoFailedException                When something fails with the crypto
     * @throws CannotEstablishOmemoSessionException When we can't establish a session with the recipient
     */
    public void sendRatchetUpdateMessage(OmemoDevice recipient)
            throws CorruptedOmemoKeyException, UndecidedOmemoIdentityException, CryptoFailedException,
            CannotEstablishOmemoSessionException, SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            getOmemoService().sendOmemoRatchetUpdateMessage(
                    new LoggedInOmemoManager(this), recipient, false);
        }
    }

    /**
     * Create a new KeyTransportElement. This message will contain the AES-Key and IV that can be used eg. for encrypted
     * Jingle file transfer.
     *
     * @param aesKey    AES key to transport
     * @param iv        Initialization vector
     * @param to        list of recipient devices
     * @return          KeyTransportMessage
     * @throws UndecidedOmemoIdentityException      When the trust of session with the recipient is not decided yet
     * @throws CorruptedOmemoKeyException           When the used identityKeys are corrupted
     * @throws CryptoFailedException                When something fails with the crypto
     * @throws CannotEstablishOmemoSessionException When we can't establish a session with the recipient
     */
    public OmemoElement createKeyTransportElement(byte[] aesKey, byte[] iv, OmemoDevice ... to)
            throws UndecidedOmemoIdentityException, CorruptedOmemoKeyException, CryptoFailedException,
            CannotEstablishOmemoSessionException, SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            return getOmemoService().prepareOmemoKeyTransportElement(
                    new LoggedInOmemoManager(this), aesKey, iv, to);
        }
    }

    /**
     * Create a new Message from a encrypted OmemoMessageElement.
     * Add ourselves as the sender and the encrypted element.
     * Also tell the server to store the message despite a possible missing body.
     * The body will be set to a hint message that we are using OMEMO.
     *
     * @param encrypted OmemoMessageElement
     * @return Message containing the OMEMO element and some additional information
     */
    Message finishMessage(OmemoElement encrypted) {
        if (encrypted == null) {
            return null;
        }

        Message chatMessage = new Message();
        chatMessage.setFrom(connection().getUser().asBareJid());
        chatMessage.addExtension(encrypted);

        if (OmemoConfiguration.getAddOmemoHintBody()) {
            chatMessage.setBody(BODY_OMEMO_HINT);
        }

        if (OmemoConfiguration.getAddMAMStorageProcessingHint()) {
            StoreHint.set(chatMessage);
        }

        if (OmemoConfiguration.getAddEmeEncryptionHint()) {
            chatMessage.addExtension(new ExplicitMessageEncryptionElement(
                    ExplicitMessageEncryptionElement.ExplicitMessageEncryptionProtocol.omemoVAxolotl));
        }

        return chatMessage;
    }

    /**
     * Returns true, if the contact has any active devices published in a deviceList.
     *
     * @param contact contact
     * @return true if contact has at least one OMEMO capable device.
     * @throws SmackException.NotConnectedException
     * @throws InterruptedException
     * @throws SmackException.NoResponseException
     */
    public boolean contactSupportsOmemo(BareJid contact)
            throws SmackException.NotConnectedException, InterruptedException, SmackException.NoResponseException,
            SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            LoggedInOmemoManager managerGuard = new LoggedInOmemoManager(this);

            getOmemoService().refreshDeviceList(managerGuard, contact);
            return !getOmemoService().getOmemoStoreBackend().loadCachedDeviceList(getOwnDevice(), contact)
                    .getActiveDevices().isEmpty();
        }
    }

    /**
     * Returns true, if the MUC with the EntityBareJid multiUserChat is non-anonymous and members only (prerequisite
     * for OMEMO encryption in MUC).
     *
     * @param multiUserChat EntityBareJid of the MUC
     * @return true if chat supports OMEMO
     * @throws XMPPException.XMPPErrorException     if
     * @throws SmackException.NotConnectedException something
     * @throws InterruptedException                 goes
     * @throws SmackException.NoResponseException   wrong
     */
    public boolean multiUserChatSupportsOmemo(EntityBareJid multiUserChat)
            throws XMPPException.XMPPErrorException, SmackException.NotConnectedException, InterruptedException,
            SmackException.NoResponseException
    {
        RoomInfo roomInfo = MultiUserChatManager.getInstanceFor(connection()).getRoomInfo(multiUserChat);
        return roomInfo.isNonanonymous() && roomInfo.isMembersOnly();
    }

    /**
     * Returns true, if the Server supports PEP.
     *
     * @param connection XMPPConnection
     * @param server domainBareJid of the server to test
     * @return true if server supports pep
     * @throws XMPPException.XMPPErrorException
     * @throws SmackException.NotConnectedException
     * @throws InterruptedException
     * @throws SmackException.NoResponseException
     */
    public static boolean serverSupportsOmemo(XMPPConnection connection, DomainBareJid server)
            throws XMPPException.XMPPErrorException, SmackException.NotConnectedException, InterruptedException,
            SmackException.NoResponseException
    {
        return ServiceDiscoveryManager.getInstanceFor(connection)
                .discoverInfo(server).containsFeature(PubSub.NAMESPACE);
    }

    /**
     * Return the fingerprint of our identity key.
     *
     * @return fingerprint
     */
    public OmemoFingerprint getOwnFingerprint()
            throws SmackException.NotLoggedInException, CorruptedOmemoKeyException
    {
        synchronized (LOCK) {
            if (getOwnJid() == null) {
                throw new SmackException.NotLoggedInException();
            }

            return getOmemoService().getOmemoStoreBackend().getFingerprint(getOwnDevice());
        }
    }

    public OmemoFingerprint getFingerprint(OmemoDevice device)
            throws CannotEstablishOmemoSessionException, SmackException.NotLoggedInException,
            CorruptedOmemoKeyException
    {
        synchronized (LOCK) {
            if (getOwnJid() == null) {
                throw new SmackException.NotLoggedInException();
            }

            if (device.equals(getOwnDevice())) {
                return getOwnFingerprint();
            }

            return getOmemoService().getOmemoStoreBackend().getFingerprint(new LoggedInOmemoManager(this), device);
        }
    }

    /**
     * Return all fingerprints of active devices of a contact.
     * @param contact contact
     * @return HashMap of deviceIds and corresponding fingerprints.
     */
    public HashMap<OmemoDevice, OmemoFingerprint> getActiveFingerprints(BareJid contact)
            throws SmackException.NotLoggedInException, CorruptedOmemoKeyException,
            CannotEstablishOmemoSessionException
    {
        synchronized (LOCK) {
            if (getOwnJid() == null) {
                throw new SmackException.NotLoggedInException();
            }

            HashMap<OmemoDevice, OmemoFingerprint> fingerprints = new HashMap<>();
            CachedDeviceList deviceList = getOmemoService().getOmemoStoreBackend()
                    .loadCachedDeviceList(getOwnDevice(), contact);

            for (int id : deviceList.getActiveDevices()) {
                OmemoDevice device = new OmemoDevice(contact, id);
                OmemoFingerprint fingerprint = getFingerprint(device);

                if (fingerprint != null) {
                    fingerprints.put(device, fingerprint);
                }
            }

            return fingerprints;
        }
    }

    public void addOmemoMessageListener(OmemoMessageListener listener) {
        omemoMessageListeners.add(listener);
    }

    public void removeOmemoMessageListener(OmemoMessageListener listener) {
        omemoMessageListeners.remove(listener);
    }

    public void addOmemoMucMessageListener(OmemoMucMessageListener listener) {
        omemoMucMessageListeners.add(listener);
    }

    public void removeOmemoMucMessageListener(OmemoMucMessageListener listener) {
        omemoMucMessageListeners.remove(listener);
    }

    /**
     * Build OMEMO sessions with devices of contact.
     *
     * @param contact contact we want to build session with.
     * @throws InterruptedException
     * @throws CannotEstablishOmemoSessionException
     * @throws SmackException.NotConnectedException
     * @throws SmackException.NoResponseException
     */
    public void buildSessionsWith(BareJid contact)
            throws InterruptedException, CannotEstablishOmemoSessionException, SmackException.NotConnectedException,
            SmackException.NoResponseException, SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            getOmemoService().buildMissingOmemoSessions(new LoggedInOmemoManager(this), contact);
        }
    }

    /**
     * Request a deviceList update from contact contact.
     *
     * @param contact contact we want to obtain the deviceList from.
     * @throws SmackException.NotConnectedException
     * @throws InterruptedException
     * @throws SmackException.NoResponseException
     */
    public void requestDeviceListUpdateFor(BareJid contact)
            throws SmackException.NotConnectedException, InterruptedException, SmackException.NoResponseException,
            SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            getOmemoService().refreshDeviceList(new LoggedInOmemoManager(this), contact);
        }
    }

    /**
     * Rotate the signedPreKey published in our OmemoBundle. This should be done every now and then (7-14 days).
     * The old signedPreKey should be kept for some more time (a month or so) to enable decryption of messages
     * that have been sent since the key was changed.
     *
     * @throws CorruptedOmemoKeyException When the IdentityKeyPair is damaged.
     * @throws InterruptedException XMPP error
     * @throws XMPPException.XMPPErrorException XMPP error
     * @throws SmackException.NotConnectedException XMPP error
     * @throws SmackException.NoResponseException XMPP error
     * @throws PubSubException.NotALeafNodeException if the bundle node on the server is a CollectionNode
     */
    public void rotateSignedPreKey()
            throws CorruptedOmemoKeyException, InterruptedException, XMPPException.XMPPErrorException,
            SmackException.NotConnectedException, SmackException.NoResponseException,
            PubSubException.NotALeafNodeException, SmackException.NotLoggedInException
    {
        synchronized (LOCK) {
            LoggedInOmemoManager managerGuard = new LoggedInOmemoManager(this);
            // generate key
            getOmemoService().getOmemoStoreBackend().changeSignedPreKey(getOwnDevice());
            // publish
            getOmemoService().publish(managerGuard);
        }
    }

    /**
     * Return true, if the given Stanza contains an OMEMO element 'encrypted'.
     * @param stanza stanza
     * @return true if stanza has extension 'encrypted'
     */
    public static boolean stanzaContainsOmemoElement(Stanza stanza) {
        return stanza.hasExtension(OmemoElement.NAME_ENCRYPTED, OMEMO_NAMESPACE_V_AXOLOTL);
    }

    /**
     * Throw an IllegalStateException if no OmemoService is set.
     */
    private void throwIfNoServiceSet() {
        if (service == null) {
            throw new IllegalStateException("No OmemoService set in OmemoManager.");
        }
    }

    /**
     * Returns a pseudo random number from the interval [1, Integer.MAX_VALUE].
     * @return deviceId
     */
    public static int randomDeviceId() {
        return new Random().nextInt(Integer.MAX_VALUE - 1) + 1;
    }

    /**
     * Return the BareJid of the user.
     *
     * @return bareJid
     */
    public BareJid getOwnJid() {
        if (ownJid == null && connection().isAuthenticated()) {
            ownJid = connection().getUser().asBareJid();
        }

        return ownJid;
    }

    /**
     * Return the deviceId of this OmemoManager.
     *
     * @return deviceId
     */
    public Integer getDeviceId() {
        synchronized (LOCK) {
            return deviceId;
        }
    }

    /**
     * Return the OmemoDevice of the user.
     *
     * @return omemoDevice
     */
    public OmemoDevice getOwnDevice() {
        synchronized (LOCK) {
            BareJid jid = getOwnJid();
            if (jid == null) {
                return null;
            }
            return new OmemoDevice(jid, getDeviceId());
        }
    }

    void setDeviceId(int nDeviceId) {
        synchronized (LOCK) {
            // Move this instance inside the HashMaps
            INSTANCES.get(connection()).remove(getDeviceId());
            INSTANCES.get(connection()).put(nDeviceId, this);

            this.deviceId = nDeviceId;
        }
    }

    /**
     * Notify all registered OmemoMessageListeners about a received OmemoMessage.
     *
     * @param decryptedBody      decrypted Body element of the message
     * @param encryptedMessage   unmodified message as it was received
     * @param wrappingMessage    message that wrapped the incoming message
     * @param messageInformation information about the messages encryption (used identityKey, carbon...)
     */
    void notifyOmemoMessageReceived(String decryptedBody,
                                    Message encryptedMessage,
                                    Message wrappingMessage,
                                    OmemoMessageInformation messageInformation)
    {
        for (OmemoMessageListener l : omemoMessageListeners) {
            l.onOmemoMessageReceived(decryptedBody, encryptedMessage, wrappingMessage, messageInformation);
        }
    }

    void notifyOmemoKeyTransportMessageReceived(CipherAndAuthTag cipherAndAuthTag,
                                                Message transportingMessage,
                                                Message wrappingMessage,
                                                OmemoMessageInformation information)
    {
        for (OmemoMessageListener l : omemoMessageListeners) {
            l.onOmemoKeyTransportReceived(cipherAndAuthTag, transportingMessage, wrappingMessage, information);
        }
    }

    /**
     * Notify all registered OmemoMucMessageListeners of an incoming OmemoMessageElement in a MUC.
     *
     * @param muc              MultiUserChat the message was received in
     * @param from             BareJid of the user that sent the message
     * @param decryptedBody    decrypted body
     * @param message          original message with encrypted content
     * @param wrappingMessage  wrapping message (in case of carbon copy)
     * @param omemoInformation information about the encryption of the message
     */
    void notifyOmemoMucMessageReceived(MultiUserChat muc,
                                       BareJid from,
                                       String decryptedBody,
                                       Message message,
                                       Message wrappingMessage,
                                       OmemoMessageInformation omemoInformation)
    {
        for (OmemoMucMessageListener l : omemoMucMessageListeners) {
            l.onOmemoMucMessageReceived(muc, from, decryptedBody, message,
                    wrappingMessage, omemoInformation);
        }
    }

    void notifyOmemoMucKeyTransportMessageReceived(MultiUserChat muc,
                                                   BareJid from,
                                                   CipherAndAuthTag cipherAndAuthTag,
                                                   Message transportingMessage,
                                                   Message wrappingMessage,
                                                   OmemoMessageInformation messageInformation)
    {
        for (OmemoMucMessageListener l : omemoMucMessageListeners) {
            l.onOmemoKeyTransportReceived(muc, from, cipherAndAuthTag,
                    transportingMessage, wrappingMessage, messageInformation);
        }
    }

    /**
     * Register stanza listeners needed for OMEMO.
     * This method is called automatically in the constructor and should only be used to restore the previous state
     * after {@link #stopListeners()} was called.
     */
    public void startStanzaListeners() {
        PEPManager pepManager = PEPManager.getInstanceFor(connection());
        CarbonManager carbonManager = CarbonManager.getInstanceFor(connection());

        // Remove listeners to avoid them getting added twice
        connection().removeAsyncStanzaListener(internalOmemoMessageStanzaListener);
        carbonManager.removeCarbonCopyReceivedListener(internalOmemoCarbonCopyListener);
        pepManager.removePEPListener(deviceListUpdateListener);

        // Add listeners
        pepManager.addPEPListener(deviceListUpdateListener);
        connection().addAsyncStanzaListener(internalOmemoMessageStanzaListener, omemoMessageStanzaFilter);
        carbonManager.addCarbonCopyReceivedListener(internalOmemoCarbonCopyListener);
    }

    /**
     * Remove active stanza listeners needed for OMEMO.
     */
    public void stopListeners() {
        PEPManager.getInstanceFor(connection()).removePEPListener(deviceListUpdateListener);
        connection().removeAsyncStanzaListener(internalOmemoMessageStanzaListener);
        CarbonManager.getInstanceFor(connection()).removeCarbonCopyReceivedListener(internalOmemoCarbonCopyListener);
    }

    /**
     * Get our connection.
     *
     * @return the connection of this manager
     */
    XMPPConnection getConnection() {
        return connection();
    }

    /**
     * Return the OMEMO service object.
     *
     * @return omemoService
     */
    OmemoService<?,?,?,?,?,?,?,?,?> getOmemoService() {
        throwIfNoServiceSet();
        return service;
    }

    private final PEPListener deviceListUpdateListener = new PEPListener() {

        LoggedInOmemoManager managerGuard = null;

        @Override
        public void eventReceived(EntityBareJid from, EventElement event, Message message) {

            if (managerGuard == null) {
                try {
                    managerGuard = new LoggedInOmemoManager(OmemoManager.this);
                } catch (SmackException.NotLoggedInException e) {
                    throw new AssertionError(e);
                }
            }

            for (ExtensionElement items : event.getExtensions()) {
                if (!(items instanceof ItemsExtension)) {
                    continue;
                }

                for (NamedElement item : ((ItemsExtension) items).getItems()) {
                    if (!(item instanceof PayloadItem<?>)) {
                        continue;
                    }

                    PayloadItem<?> payloadItem = (PayloadItem<?>) item;

                    if (!(payloadItem.getPayload() instanceof OmemoDeviceListElement_VAxolotl)) {
                        continue;
                    }

                    // Device List <list>
                    OmemoDeviceListElement_VAxolotl omemoDeviceListElement =
                            (OmemoDeviceListElement_VAxolotl) payloadItem.getPayload();
                    Integer ourDeviceId = getDeviceId();

                    getOmemoService().getOmemoStoreBackend()
                            .mergeCachedDeviceList(managerGuard.get().getOwnDevice(), from, omemoDeviceListElement);

                    if (from == null) {
                        // Unknown sender, no more work to do.
                        // TODO: This DOES happen for some reason. Figure out when...
                        continue;
                    }

                    if (!from.equals(getOwnJid())) {
                        // Not our deviceList, so nothing more to do
                        continue;
                    }

                    if (omemoDeviceListElement.getDeviceIds().contains(ourDeviceId)) {
                        // We are on the list. Nothing more to do
                        continue;
                    }

                    // Our deviceList and we are not on it! We don't want to miss all the action!!!
                    LOGGER.log(Level.INFO, "Our deviceId was not on the list!");
                    Set<Integer> deviceListIds = omemoDeviceListElement.copyDeviceIds();

                    // enroll at the deviceList
                    deviceListIds.add(ourDeviceId);
                    final OmemoDeviceListElement_VAxolotl newOmemoDeviceListElement =
                            new OmemoDeviceListElement_VAxolotl(deviceListIds);

                    // PEPListener is a synchronous listener.
                    // Avoid any deadlocks by using an async task to update the device list.
                    Async.go(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                OmemoService.publishDeviceIds(managerGuard, newOmemoDeviceListElement);
                            }
                            catch (SmackException | InterruptedException | XMPPException.XMPPErrorException e) {
                                // TODO: It might be dangerous NOT to retry publishing our deviceId
                                LOGGER.log(Level.SEVERE, "Could not publish our device list after an update " +
                                        "without our id was received: " + e.getMessage());
                            }
                        }
                    });
                }
            }
        }
    };

    private final StanzaListener internalOmemoMessageStanzaListener = new StanzaListener() {
        @Override
        public void processStanza(Stanza packet) throws SmackException.NotConnectedException, InterruptedException {
            try {
                getOmemoService().onOmemoMessageStanzaReceived(packet,
                        new LoggedInOmemoManager(OmemoManager.this));
            } catch (SmackException.NotLoggedInException e) {
                LOGGER.warning("Received OMEMO stanza while being offline: " + e);
            }
        }
    };

    private final CarbonCopyReceivedListener internalOmemoCarbonCopyListener = new CarbonCopyReceivedListener() {
        @Override
        public void onCarbonCopyReceived(CarbonExtension.Direction direction,
                                         Message carbonCopy,
                                         Message wrappingMessage) {
            if (omemoMessageStanzaFilter.accept(carbonCopy)) {
                try {
                    getOmemoService().onOmemoCarbonCopyReceived(direction, carbonCopy, wrappingMessage,
                            new LoggedInOmemoManager(OmemoManager.this));
                } catch (SmackException.NotLoggedInException e) {
                    LOGGER.warning("Received OMEMO carbon copy while being offline: " + e);
                }
            }
        }
    };

    /**
     * StanzaFilter that filters messages containing a OMEMO element.
     */
    private final StanzaFilter omemoMessageStanzaFilter = new StanzaFilter() {
        @Override
        public boolean accept(Stanza stanza) {
            return stanza instanceof Message && OmemoManager.stanzaContainsOmemoElement(stanza);
        }
    };

    public static class LoggedInOmemoManager {

        private final OmemoManager manager;

        public LoggedInOmemoManager(OmemoManager manager)
                throws SmackException.NotLoggedInException {

            if (manager == null) {
                throw new IllegalArgumentException("OmemoManager cannot be null.");
            }

            if (manager.getOwnJid() == null) {
                if (manager.getConnection().isAuthenticated()) {
                    manager.ownJid = manager.getConnection().getUser().asBareJid();
                } else {
                    throw new SmackException.NotLoggedInException();
                }
            }

            this.manager = manager;
        }

        public OmemoManager get() {
            return manager;
        }
    }

    public interface FinishedCallback {

        void initializationFinished(OmemoManager manager);

        void initializationFailed(Exception cause);
    }

    private static void initBareJidAndDeviceId(OmemoManager manager) {
        if (!manager.getConnection().isAuthenticated()) {
            throw new IllegalStateException("Connection MUST be authenticated.");
        }

        if (manager.ownJid == null) {
            manager.ownJid = manager.getConnection().getUser().asBareJid();
        }

        if (UNKNOWN_DEVICE_ID.equals(manager.deviceId)) {
            SortedSet<Integer> storedDeviceIds = manager.getOmemoService().getOmemoStoreBackend().localDeviceIdsOf(manager.ownJid);
            if (storedDeviceIds.size() > 0) {
                manager.setDeviceId(storedDeviceIds.first());
            } else {
                manager.setDeviceId(randomDeviceId());
            }
        }
    }
}
