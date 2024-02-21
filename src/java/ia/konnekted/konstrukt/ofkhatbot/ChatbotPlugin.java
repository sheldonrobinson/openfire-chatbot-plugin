package ia.konnekted.konstrukt.ofkhatbot;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Element;
import org.igniterealtime.openfire.botz.BotzConnection;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.archive.*;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.muc.*;
import org.jivesoftware.openfire.plugin.MonitoringPlugin;
import org.jivesoftware.openfire.user.UserManager;
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
import java.util.stream.Collectors;
import java.util.Collection;

public class ChatbotPlugin implements Plugin, PropertyEventListener, MUCEventListener {

    private static final Logger Log = LoggerFactory.getLogger(ChatbotPlugin.class);
    private static final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
    private static final String hostname = XMPPServer.getInstance().getServerInfo().getHostname();

    private MultiUserChatManager mucManager;
    private UserManager userManager;
    private PluginManager pluginManager;

    private BotzConnection bot;
    private ConversationManager conversationManager;
    private ArchiveSearcher archiveSearcher;
    private MonitoringPlugin plugin;

    public boolean isEnabled() {
        return enabled;
    }

    private boolean enabled;

    public Cache<JID, LinkedList<Message>> getCache() {
        return cache;
    }

    public ChatModelSettings getModel() {
        return model;
    }

    private final Cache<JID, LinkedList<Message>> cache = CacheFactory.createCache(Constants.CHATBOT_LLM_CACHE_NAME);

    private ChatModelSettings model;

    private static JID botzJid;

    public ChatbotPlugin(){
        userManager = XMPPServer.getInstance().getUserManager();
        mucManager = XMPPServer.getInstance().getMultiUserChatManager();
    }

    @Override
    public void initializePlugin(PluginManager pluginManager, File file) {
        pluginManager = pluginManager;
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
            {
                IQ iq = new IQ();
                iq.setTo(domain);
                iq.setType(IQ.Type.set);

                Element child = iq.setChildElement("vCard", "vcard-temp");
                child.addElement("FN").setText(model.getAlias());
                child.addElement("NICKNAME").setText(model.getAlias());

                bot.sendPacket(iq);

            }
            Presence presence = new Presence();
            presence.setStatus("Online");
            bot.sendPacket(presence);
        } catch (Exception e) {
            e.printStackTrace();
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
            MUCEventDispatcher.removeListener(this);
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to unload the OllamaChatbot.", ex );
        }
        CacheFactory.destroyCache(Constants.CHATBOT_LLM_CACHE_NAME);
        if (bot != null){
            bot.close();
        }
        model = null;
        mucManager = null;
        userManager = null;
        conversationManager = null;
        plugin = null;
        pluginManager = null;
    }

    @Override
    public void propertySet(String property, Map<String, Object> map) {
        if ("chatbot.enabled".equals(property)) {
            enabled = JiveGlobals.getBooleanProperty("chatbot.enabled", true);
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
        if(!cache.containsKey(jid)){
            cache.put(jid,getMessages(jid));
        }
    }

    @Override
    public void roomCreated(JID jid) {
        updateCache(jid);
        final MUCRoom mucRoom = mucManager.getMultiUserChatService(jid).getChatRoom(jid.getNode());

        boolean isOccupant = mucRoom.getOccupants().stream().anyMatch(role -> role.getUserAddress().getNode().equals(model.getAlias()));

        if(!isOccupant){
            Presence presence = new Presence();
            presence.setTo(jid);
            presence.addChildElement("x", "http://jabber.org/protocol/muc");
            bot.sendPacket(presence);
        }
    }

    @Override
    public void roomDestroyed(JID jid) {
        cache.remove(jid);
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
        updateCache(roomJID);
        LinkedList<Message> messages = cache.get(roomJID);
        messages.add(ChatbotPlugin.transform(roomJID, userJID, message));
    }

    public static Message transform(JID roomJID, JID userJID, org.xmpp.packet.Message message){
        return new Message(roomJID.toString(), userJID, roomJID, botzJid.equals(userJID)? ChatMessageType.AI:ChatMessageType.USER, message.getBody());
    }

    private static Message transform(JID roomJID, ArchivedMessage message){
        return new Message(roomJID.toString(), message.getFromJID(), message.getToJID(), botzJid.equals(message.getFromJID())? ChatMessageType.AI:ChatMessageType.USER, message.getBody());
    }

    @Override
    public void privateMessageRecieved(JID toJID, JID fromJID, org.xmpp.packet.Message message) {

    }

    @Override
    public void roomSubjectChanged(JID roomJID, JID userJID, String newSubject) {

    }

    public LinkedList<Message> getCachedMessages(JID jid){
        updateCache(jid);
        return cache.get(jid);
    }

    private LinkedList<Message> getMessages(JID jid) {
        ArchiveSearch search = new ArchiveSearch();
        search.setRoom(jid);
        Collection<Conversation> conversations = archiveSearcher.search(search);
        LinkedList<Message> msgs = (conversations != null) ? conversations.stream().flatMap(conversation -> conversation.getMessages(conversationManager).stream())
                .map(archivedMessage -> transform(jid, archivedMessage)).collect(Collectors.toCollection(LinkedList::new))
                : new LinkedList<Message>();
        if(!StringUtils.isEmpty( model.getSystemPrompt())){
            Message systemPrompt = new Message(jid.toString(), jid, jid, ChatMessageType.SYSTEM, model.getSystemPrompt());
            msgs.addFirst(systemPrompt);
        }
        return msgs;
    }
}
