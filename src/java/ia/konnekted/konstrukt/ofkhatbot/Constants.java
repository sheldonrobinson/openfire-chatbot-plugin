package ia.konnekted.konstrukt.ofkhatbot;

import org.jivesoftware.util.JiveConstants;

public class Constants {
    public static final String NAME = "chatbot";
    public static final String PLUGIN_NAME = "Chatbot";
    public static final String CHATBOT_LLM_MODEL_DEFAULT = "llama2:13b";
    public static final String CHATBOT_HOST_URL_DEFAULT = "http://localhost:11434";
    public static final String CHATBOT_ALIAS_DEFAULT = "Assistant";
    public static final String CHATBOT_SYSTEM_PROMPT_DEFAULT = "Assistant";
    public static final String CHATBOT_LLM_FORMAT_DEFAULT = "json";
    public static final double CHATBOT_LLM_TEMPERATURE_DEFAULT = 0.5;
    public static final int CHATBOT_LLM_TOP_K_DEFAULT = 40;
    public static final double CHATBOT_LLM_TOP_P_DEFAULT = 0.9;
    public static final double CHATBOT_LLM_REPEAT_PENALTY_DEFAULT = 1.5;
    public static final int CHATBOT_LLM_PREDICTIONS_DEFAULT = -2;
    public static final String CHATBOT_USERNAME = "assistant";
    public static final String CHATBOT_NICKNAME = "Assistant";
    public static final String CHATBOT_LLM_CACHE_NAME = "Chatbot Models";
    public static final long CHATBOT_MODEL_CACHE_SIZE_DEFAULT = 128*1024*1024L;
    public static final long CHATBOT_MODEL_CACHE_LIFETIME_DEFAULT = 8 * JiveConstants.HOUR;
}
