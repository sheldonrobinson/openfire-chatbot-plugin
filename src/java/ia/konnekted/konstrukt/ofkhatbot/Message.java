package ia.konnekted.konstrukt.ofkhatbot;

import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessage;
import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessageRole;
import lombok.ToString;
import lombok.Value;
import org.jivesoftware.util.cache.CacheSizes;
import org.jivesoftware.util.cache.Cacheable;
import org.jivesoftware.util.cache.CannotCalculateSizeException;

import java.io.Serializable;
@Value
public class Message extends OllamaChatMessage implements Serializable, Cacheable {

    public Message(OllamaChatMessageRole  role, String body) {
        super( role, body);
    }

    public Message(OllamaChatMessage message) {
        super( message.getRole(), message.getContent());
    }

    @Override
    public int getCachedSize() throws CannotCalculateSizeException {
        int size = 0;
        size += CacheSizes.sizeOfAnything(getRole());
        size += CacheSizes.sizeOfString(getContent());
        return size;
    }

    @Override
    public String toString() {
        return String.format("Message {\ntype: %s,\ncontent: %s\n}\n", getRole(), getContent());
    }
}
