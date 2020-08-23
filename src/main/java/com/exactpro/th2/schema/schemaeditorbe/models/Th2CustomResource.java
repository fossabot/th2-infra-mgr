package com.exactpro.th2.schema.schemaeditorbe.models;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Th2CustomResource {
    public static String GROUP = "th2.exactpro.com";
    public static String VERSION = "v1";
    public static String API_VERSION = GROUP + "/" + VERSION;

    public static class Metadata {
        private String name;
        public String getName() {
            return name;
        }
        public void setName(String name) {
            this.name = name;
        }
    }
    private String apiVersion;
    private String kind;
    private Metadata metadata;
    private Object spec;
    private String hash;

    public Th2CustomResource() {
    }

    public Th2CustomResource(ResourceEntry data) {
        setApiVersion(Th2CustomResource.API_VERSION);
        setMetadata(new Th2CustomResource.Metadata());
        getMetadata().setName(data.getName());
        setKind(data.getKind().kind());
        setSpec(data.getSpec());
        setSourceHash(data.getSourceHash());
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    public Object getSpec() {
        return spec;
    }

    public void setSpec(Object spec) {
        this.spec = spec;
    }

    @JsonIgnore
    public String getApiGroup() {
        return apiVersion.substring(0, apiVersion.indexOf("/"));
    }

    @JsonIgnore
    public String getVersion() {
        return apiVersion.substring(apiVersion.indexOf("/") + 1);
    }

    @JsonIgnore
    public String getSourceHash() {
        return hash;
    }

    @JsonIgnore
    public void setSourceHash(String hash) {
        this.hash = hash;
    }


}
