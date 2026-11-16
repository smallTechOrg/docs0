package com.docs0.source.slack;

import com.docs0.source.Source;
import com.docs0.source.SourceRepository;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** The channels an admin chose for Slack history. Reads and writes only the database. */
@Service
public class SlackHistoryConnection {

    public static final String KIND = "SLACK";
    public static final String NAME = "Slack history";
    /** The source as a whole; each document carries its own restricted flag. */
    public static final String VISIBILITY = "SHARED";
    static final String CHANNELS = "channels";

    private static final Gson GSON = new Gson();

    private final SourceRepository sources;

    public SlackHistoryConnection(SourceRepository sources) {
        this.sources = sources;
    }

    public Optional<Source> source(long tenantId) {
        return sources.find(tenantId, KIND, NAME);
    }

    /** The chosen channels. */
    public List<SlackChannel> channels(long tenantId) {
        return source(tenantId)
                .map(s -> GSON.<List<SlackChannel>>fromJson(sources.configList(tenantId, s.id(), CHANNELS),
                        new TypeToken<List<SlackChannel>>() {}.getType()))
                .orElse(List.of());
    }

    /** Replaces the selection. */
    public void setChannels(long tenantId, List<SlackChannel> channels) {
        Source source = sources.findOrCreate(tenantId, KIND, NAME, VISIBILITY);
        sources.setConfigList(tenantId, source.id(), CHANNELS, GSON.toJson(channels));
    }
}
