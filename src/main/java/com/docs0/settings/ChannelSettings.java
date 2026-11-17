package com.docs0.settings;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Per-channel switches an admin sets. Today only one: whether docs0 answers repeat questions there. */
@Repository
public class ChannelSettings {

    /** A channel to store a setting for. */
    public record Channel(String id, String name) {}

    private final JdbcClient jdbc;

    public ChannelSettings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean proactive(long tenantId, String channelId) {
        return jdbc.sql("""
                        SELECT coalesce((SELECT proactive FROM channel_setting
                                         WHERE tenant_id = :tenantId AND channel_id = :channelId), false)
                        """)
                .param("tenantId", tenantId).param("channelId", channelId)
                .query(Boolean.class).single();
    }

    /** Ids of the channels that are opted in. */
    public Set<String> proactiveChannels(long tenantId) {
        return Set.copyOf(jdbc.sql("SELECT channel_id FROM channel_setting WHERE tenant_id = :tenantId AND proactive")
                .param("tenantId", tenantId).query(String.class).list());
    }

    /** Sets the switch of every listed channel: on for the ids in {@code on}, off for the rest. */
    public void saveProactive(long tenantId, List<Channel> listed, Collection<String> on) {
        for (Channel channel : listed) {
            jdbc.sql("""
                            INSERT INTO channel_setting (tenant_id, channel_id, channel_name, proactive, updated_at)
                            VALUES (:tenantId, :channelId, :name, :proactive, now())
                            ON CONFLICT (tenant_id, channel_id)
                            DO UPDATE SET channel_name = EXCLUDED.channel_name, proactive = EXCLUDED.proactive,
                                          updated_at = now()
                            """)
                    .param("tenantId", tenantId).param("channelId", channel.id()).param("name", channel.name())
                    .param("proactive", on.contains(channel.id()))
                    .update();
        }
    }
}
