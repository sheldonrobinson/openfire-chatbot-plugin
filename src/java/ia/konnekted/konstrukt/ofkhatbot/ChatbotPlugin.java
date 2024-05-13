package ia.konnekted.konstrukt.ofkhatbot;

import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessageRole;
import io.github.amithkoujalgi.ollama4j.core.utils.PromptBuilder;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Element;
import org.igniterealtime.openfire.botz.BotzConnection;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.archive.*;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.muc.*;
import org.jivesoftware.openfire.plugin.MonitoringPlugin;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.xmpp.packet.*;

import java.io.File;
import java.util.Map;
import java.util.LinkedList;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;
import java.util.Collection;

import static java.lang.Thread.sleep;

public class ChatbotPlugin implements Plugin, PropertyEventListener, MUCEventListener {

    private static final Logger Log = LoggerFactory.getLogger(ChatbotPlugin.class);
    private static final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
    private static final String hostname = XMPPServer.getInstance().getServerInfo().getHostname();
    private MultiUserChatManager mucManager;
    private BotzConnection bot;
    private ConversationManager conversationManager;
    private ArchiveSearcher archiveSearcher;
    private MonitoringPlugin plugin;
    private final Cache<JID, LinkedList<Message>> cache = CacheFactory.createCache(Constants.CHATBOT_LLM_CACHE_NAME);
    private ChatModelSettings chatModelSettings;
    private JID botzJid;
    private boolean enabled;

    public JID getBotzJid() {
        if(botzJid == null){
            try {
                botzJid = new JID(bot.getUsername(), domain, bot.getResource());
            } catch (Exception e) {
                Log.debug("Unable to set botz JID", e);
            }
        }
        return botzJid;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Cache<JID, LinkedList<Message>> getCache() {
        return cache;
    }

    public ChatModelSettings getChatModelSettings() {
        return chatModelSettings;
    }

    public ChatbotPlugin(){
        mucManager = XMPPServer.getInstance().getMultiUserChatManager();
    }

    @Override
    public void initializePlugin(PluginManager pluginManager, File file) {
        chatModelSettings = ChatModelSettings.builder()
                .alias(JiveGlobals.getProperty("chatbot.alias",Constants.CHATBOT_ALIAS_DEFAULT))
                .model(JiveGlobals.getProperty("chatbot.llm.model",Constants.CHATBOT_LLM_MODEL_DEFAULT))
                .url(JiveGlobals.getProperty("chatbot.host.url",Constants.CHATBOT_HOST_URL_DEFAULT))
                .format(JiveGlobals.getProperty("chatbot.llm.format",Constants.CHATBOT_LLM_FORMAT_DEFAULT))
                .temperature(JiveGlobals.getDoubleProperty("chatbot.llm.temperature",Constants.CHATBOT_LLM_TEMPERATURE_DEFAULT))
                .topK(JiveGlobals.getIntProperty("chatbot.llm.top.k.sampling",Constants.CHATBOT_LLM_TOP_K_DEFAULT))
                .topP(JiveGlobals.getDoubleProperty("chatbot.llm.top.p.sampling",Constants.CHATBOT_LLM_TOP_P_DEFAULT))
                .repeatPenalty(JiveGlobals.getDoubleProperty("chatbot.llm.repeat.penalty",Constants.CHATBOT_LLM_REPEAT_PENALTY_DEFAULT))
                .predictions(JiveGlobals.getIntProperty("chatbot.llm.predictions",Constants.CHATBOT_LLM_PREDICTIONS_DEFAULT))
                .systemPrompt(JiveGlobals.getProperty("chatbot.system.prompt",Constants.CHATBOT_SYSTEM_PROMPT_DEFAULT))
                .build();

        enabled = JiveGlobals.getBooleanProperty("chatbot.enabled", true);
        bot = new BotzConnection(new ChatBotzPacketReceiver(this));
        try {
            // Create user and login
            bot.login(Constants.CHATBOT_USERNAME);
        }catch (Exception e) {
            Log.debug("Failed login", e);
        }

        try {
            IQ iq = new IQ();
            iq.setTo(domain);
            iq.setType(IQ.Type.set);

            Element child = iq.setChildElement("vCard", "vcard-temp");
            child.addElement("N").setText(chatModelSettings.getAlias());
            child.addElement("FN").setText(chatModelSettings.getAlias());
            child.addElement("NICKNAME").setText(Constants.CHATBOT_NICKNAME);

            if(!StringUtils.isEmpty(Constants.CHATBOT_AVATAR_IMAGE)){
                Element photo = child.addElement("PHOTO");
                photo.addElement("TYPE").setText("image/png");
                photo.addElement("BINVAL").setText(Constants.CHATBOT_AVATAR_IMAGE);
            }
            bot.sendPacket(iq);
         } catch (Exception e) {
             Log.debug("Failed to set nickname", e);
         }
         try {
            Presence presence = new Presence();
            presence.setStatus("Online");
            bot.sendPacket(presence);
        } catch (Exception e) {
            Log.debug("Failed set Presence to Online", e);
        }

        cache.setMaxCacheSize(JiveGlobals.getLongProperty("chatbot.model.cache.size",Constants.CHATBOT_MODEL_CACHE_SIZE_DEFAULT));
        cache.setMaxLifetime(JiveGlobals.getLongProperty("chatbot.model.cache.lifetime,",Constants.CHATBOT_MODEL_CACHE_LIFETIME_DEFAULT));

        plugin = (MonitoringPlugin) pluginManager.getPluginByName(MonitoringConstants.PLUGIN_NAME).get();

        conversationManager = plugin.getConversationManager();
        archiveSearcher = plugin.getArchiveSearcher();

        PropertyEventDispatcher.addListener(this);
        MUCEventDispatcher.addListener(this);
    }

    @Override
    public void destroyPlugin() {
        try
        {
            PropertyEventDispatcher.removeListener( this );
        }catch ( Exception ex ){
            Log.debug( "An exception occurred while trying to unload Chatbot PropertyListener.", ex );
        }
        try {
            MUCEventDispatcher.removeListener(this);
        } catch ( Exception ex ){
            Log.info( "An exception occurred while trying to unload Chatbot MUCEventListener.", ex );
        }
        try {
            CacheFactory.destroyCache(Constants.CHATBOT_LLM_CACHE_NAME);
        }catch (Exception e){
            Log.debug("An exception occurred while trying to destroy the Chatbot cache.", e);
        }try {
            Presence presence = new Presence();
            presence.setStatus("Unavailable");
            bot.sendPacket(presence);
        } catch (Exception e) {
            Log.debug("Failed set Presence to Online", e);
        }
        try {
            bot.logout();
        }catch ( Exception e){
            Log.debug("An exception occurred while trying to close the bot.", e);
        } finally {
            bot = null;
        }

        chatModelSettings = null;
        mucManager = null;
        conversationManager = null;
        plugin = null;
    }

    @Override
    public void propertySet(String property, Map<String, Object> map) {
        if(property.startsWith("chatbot.")){
            if( !property.equals("chatbot.enabled")) {
                chatModelSettings = ChatModelSettings.builder()
                        .alias(JiveGlobals.getProperty("chatbot.alias",map.containsKey("chatbot.alias")?(String) map.get("chatbot.alias"):Constants.CHATBOT_ALIAS_DEFAULT))
                        .model(JiveGlobals.getProperty("chatbot.llm.model", map.containsKey("chatbot.llm.model")?(String) map.get("chatbot.llm.model"):Constants.CHATBOT_LLM_MODEL_DEFAULT))
                        .url(JiveGlobals.getProperty("chatbot.host.url", map.containsKey("chatbot.host.url")?(String) map.get("chatbot.host.url"):Constants.CHATBOT_HOST_URL_DEFAULT))
                        .format(JiveGlobals.getProperty("chatbot.llm.format", map.containsKey("chatbot.llm.format")?(String) map.get("chatbot.llm.format"):Constants.CHATBOT_LLM_FORMAT_DEFAULT))
                        .temperature(JiveGlobals.getDoubleProperty("chatbot.llm.temperature", map.containsKey("chatbot.llm.temperature")?(Double) map.get("chatbot.llm.temperature"):Constants.CHATBOT_LLM_TEMPERATURE_DEFAULT))
                        .topK(JiveGlobals.getIntProperty("chatbot.llm.top.k.sampling", map.containsKey("chatbot.llm.top.k.sampling")?(Integer) map.get("chatbot.llm.top.k.sampling"):Constants.CHATBOT_LLM_TOP_K_DEFAULT))
                        .topP(JiveGlobals.getDoubleProperty("chatbot.llm.top.p.sampling", map.containsKey("chatbot.llm.top.p.sampling")?(Double) map.get("chatbot.llm.top.p.sampling"):Constants.CHATBOT_LLM_TOP_P_DEFAULT))
                        .repeatPenalty(JiveGlobals.getDoubleProperty("chatbot.llm.repeat.penalty", map.containsKey("chatbot.llm.repeat.penalty")?(Double) map.get("chatbot.llm.repeat.penalty"):Constants.CHATBOT_LLM_REPEAT_PENALTY_DEFAULT))
                        .predictions(JiveGlobals.getIntProperty("chatbot.llm.predictions", map.containsKey("chatbot.llm.predictions")?(Integer) map.get("chatbot.llm.predictions"):Constants.CHATBOT_LLM_PREDICTIONS_DEFAULT))
                        .systemPrompt(JiveGlobals.getProperty("chatbot.system.prompt", map.containsKey("chatbot.system.prompt")?(String) map.get("chatbot.system.prompt"):Constants.CHATBOT_SYSTEM_PROMPT_DEFAULT))
                        .build();
            }

            if( property.equals("chatbot.enabled")){
                enabled = JiveGlobals.getBooleanProperty("chatbot.enabled", true);
            }
        }
    }

    @Override
    public void propertyDeleted(String s, Map<String, Object> map) {

    }

    @Override
    public void xmlPropertySet(String s, Map<String, Object> map) {

    }

    @Override
    public void xmlPropertyDeleted(String s, Map<String, Object> map) {

    }

    public LinkedList<Message> updateCache(JID jid, LinkedList<Message> messages){
        return cache.put(jid.asBareJID(), messages);
    }

    private boolean isMember(MUCRoom mucRoom){
        return mucRoom.getMembers().stream().anyMatch(memberJID -> { return memberJID.equals(botzJid) || memberJID.asBareJID().equals(botzJid);});
    }

    @Override
    public void roomCreated(JID jid) {
        updateCache(jid, getMessages(jid));
        final MultiUserChatService service = mucManager.getMultiUserChatService(jid);
        final MUCRoom mucRoom = service.getChatRoom(jid.getNode());

        if(!mucRoom.hasOccupant(getBotzJid())){
            Presence roomPresence = new Presence();
            roomPresence.setStatus("Online");
            if(mucRoom.isLocked()){
                MUCRole owner = new MUCRole(mucRoom, mucRoom.getName(), MUCRole.Role.participant, MUCRole.Affiliation.owner, jid, roomPresence);
                try {
                    mucRoom.unlock(owner);
                } catch (Exception e) {
                    Log.debug(String.format("Failed to unlock room %s using role %s\n", mucRoom, owner), e);
                }
            }
            Lock lock = service.getChatRoomLock(jid.getNode());
            if(!isMember(mucRoom)) {
                // MUCRole botzRole = new MUCRole(mucRoom, model.getAlias(), MUCRole.Role.participant, MUCRole.Affiliation.member, getBotzJid(), roomPresence);
                MUCRole adminRole = new MUCRole(mucRoom, mucRoom.getName(), MUCRole.Role.participant, MUCRole.Affiliation.admin, jid, roomPresence);
                try {
                    lock.lock();
                    mucRoom.addMember(botzJid, chatModelSettings.getAlias(), adminRole);
                } catch (Exception e) {
                    Log.debug(String.format("Failed to add %s to %s using role %s\n", botzJid, mucRoom, adminRole),e);
                } finally {
                    lock.unlock();
                    try {
                        sleep(100L);
                    } catch (InterruptedException e) {
                        Log.trace("Interrupted while waiting to unlock", e);
                    }
                }
            }
            try {
                lock.lock();
                mucRoom.joinRoom(chatModelSettings.getAlias(),null,null,getBotzJid(),roomPresence);
            } catch (Exception e) {
                Log.debug(String.format("Member %s failed to join %s with presence %s\n", botzJid, mucRoom, roomPresence),e);
            } finally {
                lock.unlock();
                try {
                    sleep(100L);
                } catch (InterruptedException e) {
                    Log.trace("Interrupted while waiting to unlock", e);
                }
            }
            service.syncChatRoom(mucRoom);
        }
    }

    @Override
    public void roomDestroyed(JID jid) {
        cache.remove(jid.asBareJID());
    }

    @Override
    public void occupantJoined(JID jid, JID jid1, String s) {

    }

    @Override
    public void occupantLeft(JID jid, JID jid1, String s) {

    }

    @Override
    public void occupantNickKicked(JID jid, String s) {

    }

    @Override
    public void nicknameChanged(JID roomJID, JID userJID, String oldNickname, String newNickname) {

    }

    @Override
    public void messageReceived(JID roomJID, JID userJID, String nickname, org.xmpp.packet.Message message) {
    }

    public static Message transform(JID botzJid, JID roomJID, JID userJID, org.xmpp.packet.Message message){
        return new Message(botzJid.asBareJID().equals(message.getFrom().asBareJID())?OllamaChatMessageRole.ASSISTANT:OllamaChatMessageRole.USER, message.getBody());
    }

    private static Message transform(JID botzJid, JID roomJID, ArchivedMessage message){
        return new Message(botzJid.asBareJID().equals(message.getFromJID().asBareJID())? OllamaChatMessageRole.ASSISTANT:OllamaChatMessageRole.USER, message.getBody());
    }

    @Override
    public void privateMessageRecieved(JID toJID, JID fromJID, org.xmpp.packet.Message message) {
    }

    @Override
    public void roomSubjectChanged(JID roomJID, JID userJID, String newSubject) {

    }

    public LinkedList<Message> getCachedMessages(JID jid){
        if(!cache.containsKey(jid.asBareJID())){
            updateCache(jid, getMessages(jid));
        }
        return cache.get(jid.asBareJID());
    }

    public void sendChatState(JID roomJid, ChatState state){
        org.xmpp.packet.Message message = new org.xmpp.packet.Message();
        message.setType(org.xmpp.packet.Message.Type.chat);
        message.setTo(roomJid);
        message.addChildElement(state.name(), "http://jabber.org/protocol/chatstates");
        bot.sendPacket(message);
    }

    private LinkedList<Message> getMessages(JID jid) {
        LinkedList<Message> msgs = new LinkedList<Message>();
        if(!StringUtils.isEmpty( chatModelSettings.getSystemPrompt())){
            Message systemPrompt = new Message(OllamaChatMessageRole.SYSTEM, new PromptBuilder().addLine(getChatModelSettings().getSystemPrompt()).build());
            msgs.add(systemPrompt);
        }
        ArchiveSearch search = new ArchiveSearch();
        search.setSortOrder(ArchiveSearch.SortOrder.ascending);
        search.setRoom(jid);
        search.setParticipants(botzJid);
        Collection<Conversation> conversations = archiveSearcher.search(search);
        LinkedList<Message> archivedMsgs = (conversations != null) ? conversations.stream()
                .flatMap(conversation -> conversation.getMessages(conversationManager).stream())
                .filter(message -> !StringUtils.isEmpty(message.getBody()))
                .map(archivedMessage -> new Message(archivedMessage.getFromJID().asBareJID().equals(botzJid.asBareJID())?OllamaChatMessageRole.ASSISTANT: OllamaChatMessageRole.USER, archivedMessage.getBody()))
                .collect(Collectors.toCollection(LinkedList::new))
                : new LinkedList<Message>();
        msgs.addAll(archivedMsgs);
        return msgs;
    }


}
