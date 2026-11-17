package com.docs0.source.slack;

import com.google.gson.annotations.SerializedName;

/** A channel chosen for indexing, as stored in {@code source.config}: {@code {"id","name","private"}}. */
public record SlackChannel(String id, String name, @SerializedName("private") boolean isPrivate) {}
