package ia.konnekted.konstrukt.ofkhatbot;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.output.Response;
import org.apache.commons.lang3.StringUtils;
import org.igniterealtime.openfire.botz.BotzConnection;
import org.igniterealtime.openfire.botz.BotzPacketReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;

import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static dev.langchain4j.data.message.ChatMessageType.*;

public class ChatBotzPacketReceiver implements BotzPacketReceiver {

    private static final Logger Log = LoggerFactory.getLogger(ChatBotzPacketReceiver.class);
    private ExecutorService executor;
    private BotzConnection bot;
    private final ChatbotPlugin plugin;
    private ChatLanguageModel chatLanguageModel;

    public ChatBotzPacketReceiver(ChatbotPlugin plugin) {
        this.plugin = plugin;
    }
    @Override
    public void initialize(BotzConnection botzConnection) {
        this.bot = botzConnection;
        this.executor = Executors.newCachedThreadPool();
        chatLanguageModel = OllamaChatModel.builder()
                        .baseUrl(plugin.getModel().getUrl())
                        .modelName(plugin.getModel().getModel())
                        .format(plugin.getModel().getFormat())
                        .repeatPenalty(plugin.getModel().getRepeatPenalty())
                        .temperature(plugin.getModel().getTemperature())
                        .topK(plugin.getModel().getTopK())
                        .topP(plugin.getModel().getTopP())
                        .numPredict(plugin.getModel().getPredictions())
                        .build();
    }

    @Override
    public void processIncoming(Packet packet) {
        JID from = packet.getFrom();
        if (plugin.isEnabled() && !plugin.getBotzJid().equals(from)) {
            if (packet instanceof org.xmpp.packet.Message) {
                org.xmpp.packet.Message.Type type = ((org.xmpp.packet.Message) packet).getType();
                switch (type) {
                    case normal:
                    case chat:
                    case groupchat:
                        if(from.getResource() !=null){
                            // Private message , one-on-one message received, use ChatLanguageModel
                            respond((org.xmpp.packet.Message) packet);
                        }else {
                            // Use ChatModel
                            converse((org.xmpp.packet.Message) packet);
                        }
                        break;
                    case headline:
                        break;
                    case error:
                        break;
                }
            }
        }
    }

    private void converse(org.xmpp.packet.Message message) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                plugin.sendChatState(message.getFrom(), ChatState.composing);
                LinkedList<Message> messages = new LinkedList<>(plugin.getCachedMessages(message.getFrom()));
                if(!messages.getLast().getType().equals(USER)){
                    messages.add(ChatbotPlugin.transform(plugin.getBotzJid(), message.getTo(), message.getFrom(),message));
                }
                Response<AiMessage> response = chatLanguageModel.generate(messages.toArray(new Message[0]));
                org.xmpp.packet.Message newMessage = new org.xmpp.packet.Message();
                newMessage.setType(org.xmpp.packet.Message.Type.chat);
                newMessage.setTo(message.getFrom());
                newMessage.setThread(message.getThread());
                newMessage.setBody(response.content().text());
                bot.sendPacket(newMessage);
                plugin.sendChatState(message.getFrom(), ChatState.active);
            }
        });
    }

    private void respond(org.xmpp.packet.Message message) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                plugin.sendChatState(message.getFrom(), ChatState.composing);
                LinkedList<Message> messages = new LinkedList<>();
                if (!StringUtils.isEmpty(plugin.getModel().getSystemPrompt())) {
                    messages.addFirst(new Message(message.getTo().toString(), message.getFrom(), message.getTo(), SYSTEM, plugin.getModel().getSystemPrompt()));
                }
                messages.add(ChatbotPlugin.transform(plugin.getBotzJid(), message.getTo(), message.getFrom(),message));
                Response<AiMessage> response = chatLanguageModel.generate(messages.toArray(new Message[0]));
                org.xmpp.packet.Message newMessage = new org.xmpp.packet.Message();
                newMessage.setType(org.xmpp.packet.Message.Type.chat);
                newMessage.setTo(message.getFrom());
                newMessage.setThread(message.getThread());
                newMessage.setBody(response.content().text());
                bot.sendPacket(newMessage);
                plugin.sendChatState(message.getFrom(), ChatState.active);
            }
        });
    }

    @Override
    public void processIncomingRaw(String s) {

    }

    @Override
    public void terminate() {
        if (executor != null) {
            try {
                executor.shutdown();
            }catch (Exception e){
                Log.info("Unable to shutdown executor service", e);
            } finally {
                executor = null;
            }
        }
    }

}
