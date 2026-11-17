package com.docs0.source;

/** One place knowledge is synced from, for one tenant. */
public record Source(long id, long tenantId, String kind, String name, String visibility) {}
