package ia.konnekted.konstrukt.ofkhatbot;

import lombok.Value;
import org.jivesoftware.database.JiveID;
import org.jivesoftware.database.SequenceManager;
import org.jivesoftware.openfire.archive.ArchivedMessage;
import org.jivesoftware.util.cache.CacheSizes;
import org.jivesoftware.util.cache.Cacheable;
import org.jivesoftware.util.cache.CannotCalculateSizeException;
import org.xmpp.packet.JID;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ChatMessage;

import java.io.Serializable;
@Value
public class Message implements ChatMessage, Serializable, Cacheable {

    private final String thread;
    private final JID fromJID;
    private final JID toJID;
    private final ChatMessageType  type;
    private final String body;

    public Message(String thread, JID fromJID, JID toJID, ChatMessageType  type, String body) {{
        this.thread = thread;
        this.fromJID = fromJID;
        this.toJID = toJID;
        this.type = type;
        this.body = body;
    }}

    @Override
    public int getCachedSize() throws CannotCalculateSizeException {
        int size = 0;
        size += CacheSizes.sizeOfString(thread);
        size += CacheSizes.sizeOfAnything(fromJID);
        size += CacheSizes.sizeOfAnything(toJID);
        size += CacheSizes.sizeOfAnything(type);
        size += CacheSizes.sizeOfString(body);
        return size;
    }

    @Override
    public ChatMessageType type() {
        return type;
    }

    @Override
    public String text() {
        return body;
    }
}
