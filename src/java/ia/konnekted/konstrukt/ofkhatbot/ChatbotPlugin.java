package ia.konnekted.konstrukt.ofkhatbot;

import dev.langchain4j.data.message.ChatMessageType;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Element;
import org.igniterealtime.openfire.botz.BotzConnection;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.archive.*;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.muc.*;
import org.jivesoftware.openfire.plugin.MonitoringPlugin;
import org.jivesoftware.openfire.user.UserAlreadyExistsException;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.xmpp.packet.*;

import java.io.File;
import java.net.UnknownHostException;
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
    private ChatModelSettings model;
    private JID botzJid;
    private boolean enabled;

    public JID getBotzJid() {
        if(botzJid == null){
            try {
                botzJid = new JID(bot.getUsername(), domain, bot.getResource());
            } catch (Exception e) {
                Log.info("Unable to set botz JID", e);
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

    public ChatModelSettings getModel() {
        return model;
    }

    public ChatbotPlugin(){
        mucManager = XMPPServer.getInstance().getMultiUserChatManager();
    }

    @Override
    public void initializePlugin(PluginManager pluginManager, File file) {
        model = ChatModelSettings.builder()
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
            Log.info("Failed login", e);
        }

        try {
            System.out.println("Setting nickname");
            IQ iq = new IQ();
            iq.setTo(domain);
            iq.setType(IQ.Type.set);

            Element child = iq.setChildElement("vCard", "vcard-temp");
            child.addElement("N").setText(model.getAlias());
            child.addElement("FN").setText(model.getAlias());
            child.addElement("NICKNAME").setText(Constants.CHATBOT_NICKNAME);

            if(!StringUtils.isEmpty(Constants.CHATBOT_AVATAR_IMAGE)){
                Element photo = child.addElement("PHOTO");
                photo.addElement("TYPE").setText("image/png");
                photo.addElement("BINVAL").setText(Constants.CHATBOT_AVATAR_IMAGE);
            }

            bot.sendPacket(iq);
         } catch (Exception e) {
             Log.info("Failed to set nickname", e);
         }
         try {
            Presence presence = new Presence();
            presence.setStatus("Online");
            bot.sendPacket(presence);
        } catch (Exception e) {
            Log.info("Failed set Presence to Online", e);
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
            Log.info( "An exception occurred while trying to unload Chatbot PropertyListener.", ex );
        }
        try {
            MUCEventDispatcher.removeListener(this);
        } catch ( Exception ex ){
            Log.info( "An exception occurred while trying to unload Chatbot MUCEventListener.", ex );
        }
        try {
            CacheFactory.destroyCache(Constants.CHATBOT_LLM_CACHE_NAME);
        }catch (Exception e){
            Log.info("An exception occurred while trying to destroy the Chatbot cache.", e);
        }try {
            Presence presence = new Presence();
            presence.setStatus("Unavailable");
            bot.sendPacket(presence);
        } catch (Exception e) {
            Log.info("Failed set Presence to Online", e);
        }
        try {
            bot.logout();
        }catch ( Exception e){
            Log.info("An exception occurred while trying to close the bot.", e);
        } finally {
            bot = null;
        }

        model = null;
        mucManager = null;
        conversationManager = null;
        plugin = null;
    }

    @Override
    public void propertySet(String property, Map<String, Object> map) {
        if(property.startsWith("chatbot.")){
            if( !property.equals("chatbot.enabled")) {
                model = ChatModelSettings.builder()
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

    private void updateCache(JID jid){
        System.out.printf("ChatbotPlugin:updateCache<BEGIN> %s\n", jid.asBareJID());
        if(!cache.containsKey(jid.asBareJID())){
            cache.put(jid.asBareJID(),getMessages(jid));
        }
        System.out.printf("<END>ChatbotPlugin:updateCache<END> %s\n", jid.asBareJID());
    }

    private boolean isMember(MUCRoom mucRoom){
        return mucRoom.getMembers().stream().anyMatch(memberJID -> { return memberJID.equals(botzJid) || memberJID.asBareJID().equals(botzJid);});
    }

    @Override
    public void roomCreated(JID jid) {
        System.out.printf("ChatbotPlugin:roomCreated<BEGIN> %s\n", jid);
        updateCache(jid);
        final MultiUserChatService service = mucManager.getMultiUserChatService(jid);
        final MUCRoom mucRoom = service.getChatRoom(jid.getNode());

        System.out.printf("roomCreated:hasOccupant %s, %s\n", getBotzJid(), mucRoom.hasOccupant(getBotzJid()));
        if(!mucRoom.hasOccupant(getBotzJid())){
            Presence roomPresence = new Presence();
            roomPresence.setStatus("Online");
            if(mucRoom.isLocked()){
                MUCRole owner = new MUCRole(mucRoom, mucRoom.getName(), MUCRole.Role.participant, MUCRole.Affiliation.owner, jid, roomPresence);
                try {
                    mucRoom.unlock(owner);
                } catch (Exception e) {
                    System.out.printf("Failed to unlock room %s with role %s\n", mucRoom, owner);
                    e.printStackTrace();
                    Log.info(String.format("Failed to unlock room %s with role %s\n", mucRoom, owner), e);
                }
            }
            Lock lock = service.getChatRoomLock(jid.getNode());
            if(!isMember(mucRoom)) {
                // MUCRole botzRole = new MUCRole(mucRoom, model.getAlias(), MUCRole.Role.participant, MUCRole.Affiliation.member, getBotzJid(), roomPresence);
                MUCRole adminRole = new MUCRole(mucRoom, mucRoom.getName(), MUCRole.Role.participant, MUCRole.Affiliation.admin, jid, roomPresence);
                try {
                    lock.lock();
                    System.out.printf("Adding member %s to room %s\n", botzJid, mucRoom);
                    mucRoom.addMember(botzJid, model.getAlias(), adminRole);
                } catch (Exception e) {
                    System.out.printf("Failed to add member to %s with role %s\n", mucRoom, adminRole);
                    e.printStackTrace();
                    Log.info(String.format("Failed to add member %s with role %s", botzJid, adminRole), e);
                } finally {
                    lock.unlock();
                    try {
                        sleep(100L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
            try {
                lock.lock();
                System.out.printf("Member %s joining room %s\n", botzJid, mucRoom);
                mucRoom.joinRoom(model.getAlias(),null,null,getBotzJid(),roomPresence);
            } catch (Exception e) {
                System.out.printf("Failed to join room %s with presence %s\n", mucRoom, roomPresence);
                e.printStackTrace();
                Log.info(String.format("Failed to join room %s with presence %s", mucRoom, roomPresence),e);
            } finally {
                lock.unlock();
                try {
                    sleep(100L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            service.syncChatRoom(mucRoom);
        }
        System.out.printf("ChatbotPlugin:roomCreated<END> %s\n", jid);
    }

    @Override
    public void roomDestroyed(JID jid) {
        System.out.printf("ChatbotPlugin:roomDestroyed<BEGIN> %s\n", jid);
        cache.remove(jid.asBareJID());
        System.out.printf("ChatbotPlugin:roomDestroyed<END> %s\n", jid);
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
        System.out.printf("ChatbotPlugin:messageReceived<BEGIN> room=%s, user=%s, mick=%s\n%s\n", roomJID, userJID, nickname, message);
        final MultiUserChatService service = mucManager.getMultiUserChatService(roomJID);
        final MUCRoom mucRoom = service.getChatRoom(roomJID.getNode());
        // System.out.printf("messageReceived:hasOccupant %s, %s\n", getBotzJid(), mucRoom.hasOccupant(getBotzJid()));
        if(!StringUtils.isEmpty(message.getBody()) && mucRoom.hasOccupant(getBotzJid())){
            updateCache(roomJID);
            LinkedList<Message> messages = cache.get(roomJID.asBareJID());
            if(!userJID.equals(getBotzJid())){
                if(!messages.getLast().getType().equals(ChatMessageType.USER)){
                    System.out.printf("Adding USER message to cache");
                    messages.add(ChatbotPlugin.transform(getBotzJid(), roomJID, userJID, message));
                }
                System.out.printf("ChatbotPlugin:messageReceived-add-user-message: %s\n", messages);
                try {
                    bot.deliver(message);
                } catch (Exception e) {
                    System.out.printf("messageReceived: Failed to deliver message %s\n", message);
                    e.printStackTrace();
                    Log.info(String.format("Failed to deliver message %s\n", message), e);
                }
            } else {
                if(!messages.getLast().getType().equals(ChatMessageType.AI)){
                    System.out.printf("Adding AI message to cache");
                    messages.add(ChatbotPlugin.transform(getBotzJid(), roomJID, userJID, message));
                }
                System.out.printf("ChatbotPlugin:messageReceived-add-ai-message: %s\n", messages);
            }
        }
        System.out.printf("ChatbotPlugin:messageReceived<END> room=%s, user=%s, mick=%s\n", roomJID, userJID, nickname);
    }

    public static Message transform(JID botzJid, JID roomJID, JID userJID, org.xmpp.packet.Message message){
        return new Message(roomJID.asBareJID().toString(), userJID, roomJID, botzJid.equals(userJID)? ChatMessageType.AI:ChatMessageType.USER, message.getBody());
    }

    private static Message transform(JID botzJid, JID roomJID, ArchivedMessage message){
        return new Message(roomJID.asBareJID().toString(), message.getFromJID(), message.getToJID(), botzJid.equals(message.getFromJID())? ChatMessageType.AI:ChatMessageType.USER, message.getBody());
    }

    @Override
    public void privateMessageRecieved(JID toJID, JID fromJID, org.xmpp.packet.Message message) {
        System.out.printf("ChatbotPlugin:privateMessageRecieved<BEGIN> to=%s, from=%s\n%s\n", toJID, fromJID, message);
        if(!StringUtils.isEmpty(message.getBody()) && toJID.asBareJID().equals(getBotzJid().asBareJID())){
            try {
                bot.deliver(message);
            } catch (Exception e) {
                System.out.printf("privateMessageRecieved: Failed to deliver message %s\n", message);
                e.printStackTrace();
                Log.info(String.format("Failed to deliver message %s\n", message), e);
            }
        }
        System.out.printf("ChatbotPlugin:privateMessageRecieved<END> to=%s, from=%s\n", toJID, fromJID);
    }

    @Override
    public void roomSubjectChanged(JID roomJID, JID userJID, String newSubject) {

    }

    public LinkedList<Message> getCachedMessages(JID jid){
        updateCache(jid.asBareJID());
        return cache.get(jid.asBareJID());
    }

    public void sendChatState(JID roomJid, ChatState state){
        org.xmpp.packet.Message message = new org.xmpp.packet.Message();
        message.setType(org.xmpp.packet.Message.Type.groupchat);
        PacketExtension chatState = new PacketExtension(state.name(), "http://jabber.org/protocol/chatstates");
        message.addExtension(chatState);
        message.setTo(roomJid);
        bot.sendPacket(message);
    }

    private LinkedList<Message> getMessages(JID jid) {
        System.out.printf("ChatbotPlugin:getMessages %s\n", jid);
        LinkedList<Message> msgs = new LinkedList<Message>();
        if(!StringUtils.isEmpty( model.getSystemPrompt())){
            Message systemPrompt = new Message(jid.asBareJID().toString(), jid, jid, ChatMessageType.SYSTEM, model.getSystemPrompt());
            msgs.add(systemPrompt);
        }
        ArchiveSearch search = new ArchiveSearch();
        search.setRoom(jid);
        Collection<Conversation> conversations = archiveSearcher.search(search);
        LinkedList<Message> archivedMsgs = (conversations != null) ? conversations.stream()
                .flatMap(conversation -> conversation.getMessages(conversationManager).stream())
                .filter(message -> !StringUtils.isEmpty(message.getBody()))
                .map(archivedMessage -> transform(botzJid, jid, archivedMessage))
                .collect(Collectors.toCollection(LinkedList::new))
                : new LinkedList<Message>();
        msgs.addAll(archivedMsgs);
        System.out.printf("ChatbotPlugin:getMessages:msgs\n%s\n", msgs);
        System.out.printf("ChatbotPlugin:getMessages<END> %s\n", jid);
        return msgs;
    }


}
