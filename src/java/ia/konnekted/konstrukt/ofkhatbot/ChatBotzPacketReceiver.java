package ia.konnekted.konstrukt.ofkhatbot;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.output.Response;
import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.exceptions.OllamaBaseException;
import io.github.amithkoujalgi.ollama4j.core.models.Model;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.lang3.StringUtils;
import org.igniterealtime.openfire.botz.BotzConnection;
import org.igniterealtime.openfire.botz.BotzPacketReceiver;

import org.json.JSONObject;
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

        /*
        final MediaType JSON
                = MediaType.parse("application/json; charset=utf-8");
        OkHttpClient client = new OkHttpClient();
        JSONObject jo = new JSONObject();
        jo.put("model", plugin.getModel().getModel());
        RequestBody body = RequestBody.create(jo.toString(), JSON);
        String url = plugin.getModel().getUrl() + "/api/generate";
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        try(okhttp3.Response response = client.newCall(request).execute()) {
            Log.info(String.format("Response for %s : %d\n", url, response.code()));
        } catch (Exception e) {
            Log.info(String.format("Failed to request %s\n", request), e);
        }

        url = plugin.getModel().getUrl() + "/api/chat";

        request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
            Log.info(String.format("Response for %s : %d\n", url, response.code()));
        } catch (Exception e) {
            Log.info(String.format("Failed to request %s\n", request), e);
        } */
        System.out.printf("ChatBotzPacketReceiver:preloadModel<END>\n");
    }

    @Override
    public void processIncoming(Packet packet) {
        System.out.printf("ChatBotzPacketReceiver:processIncoming<BEGIN>\n%s\n", packet);
        JID from = packet.getFrom();
        if (plugin.isEnabled() && !plugin.getBotzJid().equals(from)) { //
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
                LinkedList<Message> messages = new LinkedList<>(plugin.getCachedMessages(message.getFrom()));

                if(!messages.getLast().getType().equals(USER)){
                    messages.add(ChatbotPlugin.transform(plugin.getBotzJid(), message.getTo(), message.getFrom(),message));
                }
                System.out.printf("ChatBotzPacketReceiver:converse-messages %s\n", messages);
                Response<AiMessage> response = chatLanguageModel.generate(messages.toArray(new Message[0]));
                System.out.printf("ChatBotzPacketReceiver:converse-response %s\n", response);
                org.xmpp.packet.Message newMessage = new org.xmpp.packet.Message();
                newMessage.setType(org.xmpp.packet.Message.Type.chat);
                newMessage.setTo(message.getFrom());
                if(!StringUtils.isEmpty(message.getThread())){
                    newMessage.setThread(message.getThread());
                }
                newMessage.setBody(response.content().text());
                bot.sendPacket(newMessage);
                System.out.printf("ChatBotzPacketReceiver:converse<END>\n%s\n", newMessage);
            }
        });
    }

    private void respond(org.xmpp.packet.Message message) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                System.out.printf("ChatBotzPacketReceiver:respond<BEGIN>\n%s\n", message);
                plugin.sendChatState(message.getFrom(), ChatState.composing);
                LinkedList<Message> messages = new LinkedList<>();
                if (!StringUtils.isEmpty(plugin.getModel().getSystemPrompt())) {
                    messages.addFirst(new Message(message.getTo().toString(), message.getFrom(), message.getTo(), SYSTEM, plugin.getModel().getSystemPrompt()));
                }
                messages.add(ChatbotPlugin.transform(plugin.getBotzJid(), message.getTo(), message.getFrom(),message));
                System.out.printf("ChatBotzPacketReceiver:respond-messages %s\n", messages);
                Response<AiMessage> response = chatLanguageModel.generate(messages.toArray(new Message[0]));
                System.out.printf("ChatBotzPacketReceiver:respond-response %s\n", response);
                org.xmpp.packet.Message newMessage = new org.xmpp.packet.Message();
                newMessage.setType(org.xmpp.packet.Message.Type.chat);
                newMessage.setTo(message.getFrom());
                newMessage.setThread(message.getThread());
                newMessage.setBody(response.content().text());
                bot.sendPacket(newMessage);
                System.out.printf("ChatBotzPacketReceiver:respond<END>\n%s\n", newMessage);
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
