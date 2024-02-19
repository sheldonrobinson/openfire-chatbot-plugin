package ia.konnekted.konstrukt.ofkhatbot;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.Value;
import org.jivesoftware.util.cache.CacheSizes;
import org.jivesoftware.util.cache.Cacheable;
import org.jivesoftware.util.cache.CannotCalculateSizeException;

import java.io.*;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import dev.langchain4j.model.ollama.OllamaChatModel;
import org.jivesoftware.util.cache.ExternalizableUtil;

@Value
public class KhatLanguageModel implements Serializable, Cacheable {
    private ChatLanguageModel model;
    @Override
    public int getCachedSize() throws CannotCalculateSizeException {
        int size = 0;
        size += CacheSizes.sizeOfObject();
        size += CacheSizes.sizeOfAnything(this.model);
        return size;
    }

}
