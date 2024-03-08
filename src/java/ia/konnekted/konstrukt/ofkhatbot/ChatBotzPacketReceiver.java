package ia.konnekted.konstrukt.ofkhatbot;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.OllamaStreamHandler;
import io.github.amithkoujalgi.ollama4j.core.exceptions.OllamaBaseException;
import io.github.amithkoujalgi.ollama4j.core.models.Model;
import io.github.amithkoujalgi.ollama4j.core.models.chat.*;
import io.github.amithkoujalgi.ollama4j.core.utils.Options;
import io.github.amithkoujalgi.ollama4j.core.utils.OptionsBuilder;
import io.github.amithkoujalgi.ollama4j.core.utils.PromptBuilder;
import org.apache.commons.lang3.StringUtils;
import org.igniterealtime.openfire.botz.BotzConnection;
import org.igniterealtime.openfire.botz.BotzPacketReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ChatBotzPacketReceiver implements BotzPacketReceiver {

    private static final Logger Log = LoggerFactory.getLogger(ChatBotzPacketReceiver.class);
    private ExecutorService executor;
    private BotzConnection bot;
    private final ChatbotPlugin plugin;

    private Options options;
    private PromptBuilder prompt;

    public ChatBotzPacketReceiver(ChatbotPlugin plugin) {
        this.plugin = plugin;
    }
    @Override
    public void initialize(BotzConnection botzConnection) {
        this.bot = botzConnection;
        this.executor = Executors.newCachedThreadPool();
        options = new OptionsBuilder()
                .setNumPredict(plugin.getModel().getPredictions())
                .setRepeatPenalty((float) plugin.getModel().getRepeatPenalty())
                .setTopK(plugin.getModel().getTopK())
                .setTopP((float) plugin.getModel().getTopP())
                .setTemperature((float) plugin.getModel().getTemperature())
                .build();

        prompt = new PromptBuilder().addLine(plugin.getModel().getSystemPrompt());
        preloadModel();
    }

    private void preloadModel(){
        System.out.printf("ChatBotzPacketReceiver:preloadModel<BEGIN>\n");

        OllamaAPI ollamaAPI = plugin.getModel().getUrl().startsWith("http") ? new OllamaAPI(plugin.getModel().getUrl()) : new OllamaAPI("http://"+plugin.getModel().getUrl());

        try {
            List<Model> models = ollamaAPI.listModels();
            boolean found = models.stream().anyMatch(model -> { return model.getModelName().startsWith(plugin.getModel().getModel());});
            if (!found) {
                ollamaAPI.pullModel(plugin.getModel().getModel());
            }
        } catch (OllamaBaseException | IOException | InterruptedException | URISyntaxException e) {
            e.printStackTrace();
        }
        System.out.printf("ChatBotzPacketReceiver:preloadModel<END>\n");
    }

    @Override
    public void processIncoming(Packet packet) {
        System.out.printf("ChatBotzPacketReceiver:processIncoming<BEGIN>\n%s\n", packet);
        JID from = packet.getFrom();
        if (plugin.isEnabled() && !plugin.getBotzJid().asBareJID().equals(from.asBareJID())) { //
            System.out.println("ChatBotzPacketReceiver:processIncoming:User");
            if (packet instanceof org.xmpp.packet.Message && !StringUtils.isEmpty(((org.xmpp.packet.Message) packet).getBody())) {
                System.out.println("ChatBotzPacketReceiver:processIncoming:Message");
                org.xmpp.packet.Message.Type type = ((org.xmpp.packet.Message) packet).getType();
                System.out.printf("ChatBotzPacketReceiver:processIncoming:type: %s\n", type);
                switch (type) {
                    case chat:
                    case groupchat:
                        // if(from.getResource() !=null){
                        // Private message , one-on-one message received, use ChatLanguageModel
                        converse((org.xmpp.packet.Message) packet);
                        break;
                    case normal:
                         // Use ChatModel
                        respond((org.xmpp.packet.Message) packet);
                        break;
                    case headline:
                        break;
                    case error:
                        break;
                }
            }
        }
        System.out.println("ChatBotzPacketReceiver:processIncoming<END>");
    }

    private void converse(org.xmpp.packet.Message message) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                System.out.printf("ChatBotzPacketReceiver:converse<BEGIN>\n%s\n", message);
                plugin.sendChatState(message.getFrom(), ChatState.composing);
                OllamaAPI ollamaAPI = plugin.getModel().getUrl().startsWith("http") ? new OllamaAPI(plugin.getModel().getUrl()) : new OllamaAPI("http://"+plugin.getModel().getUrl());
                StringBuilder sb = new StringBuilder();
                OllamaStreamHandler streamHandler = (s) -> {
                    System.out.println(s);
                    sb.append(s);
                };

                List<OllamaChatMessage> messages = plugin.getCachedMessages(message.getFrom().asBareJID()).stream().map(msg -> new OllamaChatMessage(msg.getRole(), msg.getContent())).collect(Collectors.toList());
                messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, message.getBody()));

                OllamaChatRequestModel request = OllamaChatRequestBuilder.getInstance(plugin.getModel().getModel())
                        .withMessages(messages)
                        .withGetJsonResponse()
                        .withStreaming()
                        .withOptions(options)
                        .build();
                OllamaChatResult result = null;
                try {
                    result = ollamaAPI.chat(request, streamHandler);
                    System.out.printf("ChatBotzPacketReceiver:converse-response\n-------------------------------\n%s\n--------------------------------", sb.toString());
                } catch (OllamaBaseException | IOException | InterruptedException e) {
                    e.printStackTrace();
                }

                if (result != null && result.getHttpStatusCode() == 200  && !StringUtils.isEmpty(result.getResponse())) {
                    LinkedList<Message> updated = result.getChatHistory().stream().map(message -> new Message(message)).collect(Collectors.toCollection(LinkedList::new));
                    System.out.printf("ChatBotzPacketReceiver:converse-messages %s\n", updated);
                    plugin.updateCache(message.getFrom(), updated);
                    org.xmpp.packet.Message newMessage = new org.xmpp.packet.Message();
                    newMessage.setType(org.xmpp.packet.Message.Type.chat);
                    newMessage.setTo(message.getFrom());
                    if(!StringUtils.isEmpty(message.getThread())){
                        newMessage.setThread(message.getThread());
                    }
                    newMessage.setBody(result.getResponse());
                    System.out.printf("ChatBotzPacketReceiver:converse-message\n%s\n", newMessage);
                    bot.sendPacket(newMessage);
                    
                }
                System.out.println("ChatBotzPacketReceiver:converse<END>");

            }
        });
    }

    private void respond(org.xmpp.packet.Message message) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                System.out.printf("ChatBotzPacketReceiver:respond<BEGIN>\n%s\n", message);
                plugin.sendChatState(message.getFrom(), ChatState.composing);
                OllamaAPI ollamaAPI = plugin.getModel().getUrl().startsWith("http") ? new OllamaAPI(plugin.getModel().getUrl()) : new OllamaAPI("http://"+plugin.getModel().getUrl());
                StringBuilder sb = new StringBuilder();
                OllamaStreamHandler streamHandler = (s) -> {
                    System.out.println(s);
                    sb.append(s);
                };

                List<OllamaChatMessage> messages = new LinkedList<>();
                if (!StringUtils.isEmpty(plugin.getModel().getSystemPrompt())) {
                    messages.add(new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, plugin.getModel().getSystemPrompt()));
                }
                messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, message.getBody()));

                OllamaChatRequestModel request = OllamaChatRequestBuilder.getInstance(plugin.getModel().getModel())
                        .withMessages(messages)
                        .withGetJsonResponse()
                        .withStreaming()
                        .withOptions(options)
                        .build();
                OllamaChatResult result = null;
                try {
                    result = ollamaAPI.chat(request, streamHandler);
                    System.out.printf("ChatBotzPacketReceiver:respond-response\n-------------------------------\n%s\n--------------------------------", sb.toString());
                } catch (OllamaBaseException | IOException | InterruptedException e) {
                    e.printStackTrace();
                }

                if (result != null && result.getHttpStatusCode() == 200  && !StringUtils.isEmpty(result.getResponse())) {
                    org.xmpp.packet.Message newMessage = new org.xmpp.packet.Message();
                    newMessage.setType(org.xmpp.packet.Message.Type.chat);
                    newMessage.setTo(message.getFrom());
                    if(!StringUtils.isEmpty(message.getThread())){
                        newMessage.setThread(message.getThread());
                    }
                    newMessage.setBody(result.getResponse());
                    System.out.printf("ChatBotzPacketReceiver:respond-message\n%s\n", newMessage);
                    bot.sendPacket(newMessage);
                }
                System.out.println("ChatBotzPacketReceiver:respond<END>");
            }
        });
    }

    @Override
    public void processIncomingRaw(String s) {
        System.out.printf("processIncomingRaw: %s\n", s);
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
