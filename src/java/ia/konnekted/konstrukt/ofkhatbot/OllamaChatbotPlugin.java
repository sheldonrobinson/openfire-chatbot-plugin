package ia.konnekted.konstrukt.ofkhatbot;

import org.dom4j.Element;
import org.igniterealtime.openfire.botz.BotzConnection;
import org.igniterealtime.openfire.botz.BotzPacketReceiver;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import com.reucon.openfire.plugin.archive.PersistenceManager;
import com.reucon.openfire.plugin.archive.model.Conversation;
import com.reucon.openfire.plugin.archive.model.ArchivedMessage;
import org.jivesoftware.openfire.archive.ConversationManager;
import org.jivesoftware.openfire.archive.ArchiveSearcher;
import org.jivesoftware.openfire.muc.*;
import org.jivesoftware.openfire.plugin.MonitoringPlugin;
import org.jivesoftware.openfire.archive.MonitoringConstants;
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
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.Collection;

public class OllamaChatbotPlugin implements Plugin, PropertyEventListener, MUCEventListener {

    private static final Logger Log = LoggerFactory.getLogger(OllamaChatbotPlugin.class);
    private ExecutorService executor;

    private static final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
    private static final String hostname = XMPPServer.getInstance().getServerInfo().getHostname();

    private MultiUserChatManager mucManager;
    private UserManager userManager;
    private PluginManager pluginManager;
    private ConversationManager conversationManager;
    private ArchiveSearcher archiveSearcher;
    private MonitoringPlugin plugin;
    private boolean enabled;
    private final Cache<JID, KhatLanguageModel> cache = CacheFactory.createCache(Constants.CHATBOT_LLM_CACHE_NAME);

    private OllamaSettings model;

    private static OllamaChatbotPlugin instance;

    private static JID botzJid;

    public OllamaChatbotPlugin(){
        instance = this;
        userManager = XMPPServer.getInstance().getUserManager();
        mucManager = XMPPServer.getInstance().getMultiUserChatManager();
    }

    public static OllamaChatbotPlugin getInstance() {
        if(instance == null){
            new OllamaChatbotPlugin();
        }
        return instance;
    }

    @Override
    public void initializePlugin(PluginManager pluginManager, File file) {
        pluginManager = pluginManager;
        enabled = JiveGlobals.getBooleanProperty("chatbot.enabled", true);

        model = OllamaSettings.builder()
                .alias(JiveGlobals.getProperty("chatbot.alias",Constants.CHATBOT_ALIAS_DEFAULT))
                .model(JiveGlobals.getProperty("chatbot.llm.model",Constants.CHATBOT_LLM_MODEL_DEFAULT))
                .url(JiveGlobals.getProperty("chatbot.host.url",Constants.CHATBOT_HOST_URL_DEFAULT))
                .format(JiveGlobals.getProperty("chatbot.llm.format",Constants.CHATBOT_LLM_FORMAT_DEFAULT))
                .temperature(JiveGlobals.getDoubleProperty("chatbot.llm.temperature",Constants.CHATBOT_LLM_TEMPERATURE_DEFAULT))
                .topK(JiveGlobals.getIntProperty("chatbot.llm.top.k.sampling",Constants.CHATBOT_LLM_TOP_K_DEFAULT))
                .topP(JiveGlobals.getDoubleProperty("chatbot.llm.top.p.sampling",Constants.CHATBOT_LLM_TOP_P_DEFAULT))
                .repeatPenalty((JiveGlobals.getDoubleProperty("chatbot.llm.repeat.penalty",Constants.CHATBOT_LLM_REPEAT_PENALTY_DEFAULT)))
                .predictions((JiveGlobals.getIntProperty("chatbot.llm.predictions",Constants.CHATBOT_LLM_PREDICTIONS_DEFAULT)))
                .build();


        BotzPacketReceiver packetReceiver = new BotzPacketReceiver() {
            BotzConnection bot;

            public void initialize(BotzConnection bot) {
                this.bot = bot;
            }

            @Override
            public void processIncoming(Packet packet) {
                JID from = packet.getFrom();
                if (enabled && !botzJid.equals(from)) {
                    if (packet instanceof Message) {
                        org.xmpp.packet.Message.Type type = ((Message) packet).getType();
                        switch (type) {
                            case normal:
                                break;
                            case chat:
                                break;
                            case groupchat:
                                break;
                            case headline:
                                break;
                            case error:
                                break;
                        }
                        // Echo back to sender
                        packet.setTo(packet.getFrom());
                        bot.sendPacket(packet);
                    } else if (packet instanceof Presence) {
                        // Echo back to sender
                        Presence presence = new Presence();
                        presence.setStatus("Online");
                        presence.setTo(packet.getFrom());
                        bot.sendPacket(packet);
                    }
                }
            }
            public void processIncomingRaw(String rawText) { };

            public void terminate() { };
        };

        BotzConnection bot = new BotzConnection(packetReceiver);
        try {
            // Create user and login
            bot.login(Constants.CHATBOT_USERNAME);

            botzJid = new JID(bot.getUsername(), bot.getHostName(), bot.getResource());
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
        model = null;
        mucManager = null;
        userManager = null;
        conversationManager = null;
        plugin = null;
        pluginManager = null;
        instance = null;

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

    @Override
    public void roomCreated(JID jid) {
        if(!cache.containsKey(jid)){

        }else {
            PersistenceManager persistenceManager = plugin.getPersistenceManager(jid);
            Collection<Conversation> conversations = persistenceManager.findConversations(null, null, null, jid, null);
            Collection<ArchivedMessage> messages = conversations.stream().flatMap( conversation -> conversation.getMessages().stream())
                    .collect(Collectors.toList());
        }
        MultiUserChatService service = mucManager.getMultiUserChatService(jid);
        try {
            MUCRoom room = service.getChatRoom(jid.getNode(),botzJid);

        } catch (NotAllowedException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void roomDestroyed(JID jid) {

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
    public void nicknameChanged(JID jid, JID jid1, String s, String s1) {

    }

    @Override
    public void messageReceived(JID jid, JID jid1, String s, Message message) {

    }

    @Override
    public void privateMessageRecieved(JID jid, JID jid1, Message message) {

    }

    @Override
    public void roomSubjectChanged(JID jid, JID jid1, String s) {

    }
}
