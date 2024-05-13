package ia.konnekted.konstrukt.ofkhatbot;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.OllamaStreamHandler;
import io.github.amithkoujalgi.ollama4j.core.exceptions.OllamaBaseException;
import io.github.amithkoujalgi.ollama4j.core.models.Model;
import io.github.amithkoujalgi.ollama4j.core.models.chat.*;
import io.github.amithkoujalgi.ollama4j.core.utils.Options;
import io.github.amithkoujalgi.ollama4j.core.utils.OptionsBuilder;
import io.github.amithkoujalgi.ollama4j.core.utils.PromptBuilder;
import lombok.extern.flogger.Flogger;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.lang.Thread.sleep;

public class ChatBotzPacketReceiver implements BotzPacketReceiver {

    private static final String NO_ANSWER = "I was unable to fulfil your request, you may try again.";
    private static final Logger Log = LoggerFactory.getLogger(ChatBotzPacketReceiver.class);
    private ExecutorService executor;
    private BotzConnection bot;
    private final ChatbotPlugin plugin;

    private Options options;
    private String prompt;

    public ChatBotzPacketReceiver(ChatbotPlugin plugin) {
        this.plugin = plugin;
    }
    @Override
    public void initialize(BotzConnection botzConnection) {
        this.bot = botzConnection;
        this.executor = Executors.newCachedThreadPool();
        options = new OptionsBuilder()
                .setNumPredict(plugin.getChatModelSettings().getPredictions())
                .setRepeatPenalty((float) plugin.getChatModelSettings().getRepeatPenalty())
                .setTopK(plugin.getChatModelSettings().getTopK())
                .setTopP((float) plugin.getChatModelSettings().getTopP())
                .setTemperature((float) plugin.getChatModelSettings().getTemperature())
                .build();

        prompt = new PromptBuilder().addLine(plugin.getChatModelSettings().getSystemPrompt()).build();
        preloadModel();
    }

    private void preloadModel(){
        OllamaAPI ollamaAPI = plugin.getChatModelSettings().getUrl().startsWith("http") ? new OllamaAPI(plugin.getChatModelSettings().getUrl()) : new OllamaAPI("http://"+plugin.getChatModelSettings().getUrl());
        try {
            for (int count = 0; !ollamaAPI.ping() && count < 10; count++) {
                try {
                    sleep(100);
                } catch (InterruptedException e) {
                    Log.debug("Interrupted while pinging ollama API", e);
                }
            }
        }catch (Exception e){
            Log.debug("Problem encountered pinging ollama API", e);
        }
        try {
            List<Model> models = ollamaAPI.listModels();
            boolean found = models.stream().anyMatch(model -> { return model.getModelName().startsWith(plugin.getChatModelSettings().getModel());});
            if (!found) {
                ollamaAPI.pullModel(plugin.getChatModelSettings().getModel());
            }
        } catch (OllamaBaseException | IOException | InterruptedException | URISyntaxException e) {
            Log.error("Failed to load AI model",e);
        }
    }

    @Override
    public void processIncoming(Packet packet) {
        if (plugin.isEnabled() && plugin.getBotzJid().equals(packet.getTo())) { //
            if (packet instanceof org.xmpp.packet.Message && !StringUtils.isEmpty(((org.xmpp.packet.Message) packet).getBody())) {
                org.xmpp.packet.Message.Type type = ((org.xmpp.packet.Message) packet).getType();
                switch (type) {
                    case chat:
                    case groupchat:
                        JID from = packet.getFrom();
                        // Private message , one-on-one message received, use ChatLanguageModel
                        if(!plugin.getChatModelSettings().getAlias().equals(from.getResource())){
                            converse((org.xmpp.packet.Message) packet);
                        }
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
    }

    private void converse(org.xmpp.packet.Message message) {
        Log.info("converse: {}", message);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                OllamaAPI ollamaAPI = plugin.getChatModelSettings().getUrl().startsWith("http") ? new OllamaAPI(plugin.getChatModelSettings().getUrl()) : new OllamaAPI("http://"+plugin.getChatModelSettings().getUrl());
                ollamaAPI.setVerbose(true);
                StringBuilder sb = new StringBuilder();
                AtomicBoolean completed = new AtomicBoolean(false);
                OllamaStreamHandler streamHandler = (s) -> {
                    if(s.length() <= sb.length()) {
                        completed.set(true);
                        Log.info("OllamaStreamHandler.Result: {}", sb.toString());
                    } else {
                        plugin.sendChatState(message.getFrom(), ChatState.composing);
                        String msg = s.substring(sb.length() , s.length());
                        sb.append(msg);

                    }
                };


                List<OllamaChatMessage> messages = plugin.getCachedMessages(message.getFrom().asBareJID()).stream().map(msg -> new OllamaChatMessage(msg.getRole(), msg.getContent())).collect(Collectors.toList());
                OllamaChatRequestModel request = OllamaChatRequestBuilder.getInstance(plugin.getChatModelSettings().getModel())
                        .withMessages(messages)
                        .withMessage(OllamaChatMessageRole.USER, message.getBody())
                        .withOptions(options)
                        .withStreaming()
                        .build();
                OllamaChatResult result = null;
                try {
                    try {
                        for (int count = 0; !ollamaAPI.ping() && count < 10; count++) {
                            try {
                                sleep(100);
                            } catch (InterruptedException e) {
                                Log.info("Interrupted while pinging ollama API", e);
                            }
                        }
                    }catch(Exception  e) {
                        Log.info("Problem encountered pinging ollama API", e);
                    }

                    result = ollamaAPI.chat(request, streamHandler);
                    while (!completed.get()) {
                           sleep(100);
                    }
                } catch (OllamaBaseException | IOException | InterruptedException e) {
                    Log.info(String.format("Problem encountered performing request: %s", request),e);
                }

                Log.info("Converse.Result: {}", sb.toString());

                if (result != null && result.getHttpStatusCode() == 200  && !StringUtils.isEmpty(result.getResponse())) {
                    LinkedList<Message> updated = result.getChatHistory().stream().map(message -> new Message(message)).collect(Collectors.toCollection(LinkedList::new));
                    plugin.updateCache(message.getFrom(), updated);
                    org.xmpp.packet.Message newMessage = new org.xmpp.packet.Message();
                    newMessage.setType(org.xmpp.packet.Message.Type.groupchat);
                    newMessage.setTo(message.getFrom().asBareJID());
                    if(!StringUtils.isEmpty(message.getThread())){
                        newMessage.setThread(message.getThread());
                    }
                    newMessage.setBody(result.getResponse());
                    bot.sendPacket(newMessage);
                    
                } else {
                    LinkedList<Message> updated = messages.stream().map(message -> new Message(message)).collect(Collectors.toCollection(LinkedList::new));
                    updated.add(new Message(OllamaChatMessageRole.USER, message.getBody()));
                    String answer = sb.length() > 0 ? sb.toString() : NO_ANSWER;
                    updated.add(new Message(OllamaChatMessageRole.ASSISTANT, answer));
                    plugin.updateCache(message.getFrom(), updated);
                    org.xmpp.packet.Message newMessage = new org.xmpp.packet.Message();
                    newMessage.setType(org.xmpp.packet.Message.Type.groupchat);
                    newMessage.setTo(message.getFrom().asBareJID());
                    if(!StringUtils.isEmpty(message.getThread())){
                        newMessage.setThread(message.getThread());
                    }
                    newMessage.setBody(answer);
                    bot.sendPacket(newMessage);
                }
            }
        });
    }

    private void respond(org.xmpp.packet.Message message) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                OllamaAPI ollamaAPI = plugin.getChatModelSettings().getUrl().startsWith("http") ? new OllamaAPI(plugin.getChatModelSettings().getUrl()) : new OllamaAPI("http://"+plugin.getChatModelSettings().getUrl());
                ollamaAPI.setVerbose(true);
                StringBuilder sb = new StringBuilder();
                AtomicBoolean completed = new AtomicBoolean(false);
                OllamaStreamHandler streamHandler = (s) -> {
                    if(s.length() <= sb.length()) {
                        completed.set(true);
                    } else {
                        plugin.sendChatState(message.getFrom(), ChatState.composing);
                        String msg = s.substring(sb.length(), s.length());
                        sb.append(msg);
                    }
                };

                List<OllamaChatMessage> messages = new LinkedList<>();


                OllamaChatRequestModel request = (!StringUtils.isEmpty(plugin.getChatModelSettings().getSystemPrompt()) ?
                        OllamaChatRequestBuilder.getInstance(plugin.getChatModelSettings().getModel()).withMessage(OllamaChatMessageRole.SYSTEM, prompt)
                        : OllamaChatRequestBuilder.getInstance(plugin.getChatModelSettings().getModel()))
                        .withMessage(OllamaChatMessageRole.USER, message.getBody())
                        .withOptions(options)
                        .withStreaming()
                        .build();
                OllamaChatResult result = null;
                try {
                    try {
                        for (int count = 0; !ollamaAPI.ping() && count < 10; count++) {
                            try {
                                sleep(100);
                            } catch (InterruptedException e) {
                                Log.debug("Interrupted while pinging ollama API", e);
                            }
                        }
                    }catch(Exception  e) {
                        Log.debug("Problem encountered pinging ollama API", e);
                    }
                    result = ollamaAPI.chat(request, streamHandler);
                    while (!completed.get()) {
                            sleep(100);
                    }
                } catch (OllamaBaseException | IOException | InterruptedException e) {
                    Log.info(String.format("Problem encountered performing request: %s", request),e);
                }

                if (result != null && result.getHttpStatusCode() == 200  && !StringUtils.isEmpty(result.getResponse())) {
                    org.xmpp.packet.Message newMessage = new org.xmpp.packet.Message();
                    newMessage.setType(org.xmpp.packet.Message.Type.normal);
                    newMessage.setTo(message.getFrom());
                    if(!StringUtils.isEmpty(message.getThread())){
                        newMessage.setThread(message.getThread());
                    }
                    newMessage.setBody(result.getResponse());
                    bot.sendPacket(newMessage);
                } else {
                    String answer = sb.length() > 0 ? sb.toString() : NO_ANSWER;
                    org.xmpp.packet.Message newMessage = new org.xmpp.packet.Message();
                    newMessage.setType(org.xmpp.packet.Message.Type.normal);
                    newMessage.setTo(message.getFrom());
                    if(!StringUtils.isEmpty(message.getThread())){
                        newMessage.setThread(message.getThread());
                    }
                    newMessage.setBody(answer);
                    bot.sendPacket(newMessage);
                }
            }
        });
    }

    @Override
    public void processIncomingRaw(String s) {
        Log.trace(String.format("<EXEC>\n%s",s),new Exception());
    }

    @Override
    public void terminate() {
        if (executor != null) {
            try {
                executor.shutdown();
            }catch (Exception e){
                Log.debug("Unable to shutdown executor service", e);
            } finally {
                executor = null;
            }
        }
    }

}
