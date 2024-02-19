package ia.konnekted.konstrukt.ofkhatbot;

import lombok.Builder;
import lombok.Value;

@Builder
@Value
public class OllamaSettings {
    @Builder.Default private String alias = Constants.CHATBOT_ALIAS_DEFAULT;
    @Builder.Default private String url = Constants.CHATBOT_HOST_URL_DEFAULT;
    @Builder.Default private String systemPrompt = Constants.CHATBOT_SYSTEM_PROMPT_DEFAULT;
    @Builder.Default private String model = Constants.CHATBOT_LLM_MODEL_DEFAULT;
    @Builder.Default private String format = Constants.CHATBOT_LLM_FORMAT_DEFAULT;
    @Builder.Default private double temperature = Constants.CHATBOT_LLM_TEMPERATURE_DEFAULT;
    @Builder.Default private int topK = Constants.CHATBOT_LLM_TOP_K_DEFAULT;
    @Builder.Default private double topP = Constants.CHATBOT_LLM_TOP_P_DEFAULT;
    @Builder.Default private double repeatPenalty = Constants.CHATBOT_LLM_REPEAT_PENALTY_DEFAULT;
    @Builder.Default private int predictions = Constants.CHATBOT_LLM_PREDICTIONS_DEFAULT;

}
