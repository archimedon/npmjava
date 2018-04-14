package com.rdnsn.b2intgr;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import javax.validation.constraints.NotNull;
import java.util.Base64;

@JsonAutoDetect(fieldVisibility= JsonAutoDetect.Visibility.ANY)
public class CloudFSConfiguration {

    /**
     * Configure the Remote server
     */
    @NotNull
    @JsonProperty
    private RemoteStorageConfiguration remoteStorageConf;

    @NotNull
    @JsonProperty
    private Neo4JConfiguration neo4jConf;

    @NotNull
    @JsonProperty
    private String host;

    @NotNull
    @JsonProperty
    private int port;

    @NotNull
    @JsonProperty
    private String contextUri;

    @NotNull
    @JsonProperty
    private String docRoot;

    @NotNull
    @JsonProperty
    private String protocol;

    @NotNull
    @JsonProperty
    private String customSeparator = "\\^";

    @NotNull
    @JsonProperty
    private String adminEmail;

    @NotNull
    @JsonProperty
    private int maximumRedeliveries = 4;

    @NotNull
    @JsonProperty
    private int redeliveryDelay = 5000; // milliseconds

    @NotNull
    @JsonProperty
    private int poolSize = 5; // number of clients in pool

    @NotNull
    @JsonProperty
    private int maxPoolSize = 10; // max number of clients in pool

    @JsonProperty
    private MailConfig mailConfig;

    @JsonProperty
    private double backOffMultiplier;

    public MailConfig getMailConfig() {
        return mailConfig;
    }

    public CloudFSConfiguration setMailConfig(MailConfig mailConfig) {
        this.mailConfig = mailConfig;
        return this;
    }

    public Neo4JConfiguration getNeo4jConf() {
        return neo4jConf;
    }

    public CloudFSConfiguration setNeo4jConf(Neo4JConfiguration neo4jConf) {
        this.neo4jConf = neo4jConf;
        return this;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
    }

    public int getMaximumRedeliveries() {
        return maximumRedeliveries;
    }

    public void setMaximumRedeliveries(int maximumRedeliveries) {
        this.maximumRedeliveries = maximumRedeliveries;
    }

    public int getRedeliveryDelay() {
        return redeliveryDelay;
    }

    public void setRedeliveryDelay(int redeliveryDelay) {
        this.redeliveryDelay = redeliveryDelay;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getDocRoot() {
        return docRoot;
    }

    public void setDocRoot(String docRoot) {
        this.docRoot = docRoot;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getCustomSeparator() {
        return customSeparator;
    }

    public void setCustomSeparator(String customSeparator) {
        this.customSeparator = customSeparator;
    }

    public String getContextUri() {
        return contextUri;
    }

    public void setContextUri(String contextUri) {
        this.contextUri = contextUri;
    }

    public String getRemoteBucketId() {
        return remoteStorageConf.getBucketId();
    }

    public String getRemoteBucketName() {
        return remoteStorageConf.getBucketName();
    }

    public String getRemoteAccountId() {
        return remoteStorageConf.getAccountId();
    }

    public String getRemoteApplicationKey() {
        return remoteStorageConf.getApplicationKey();
    }

    public String getRemoteAuthenticationUrl() {
        return remoteStorageConf.getAuthenticationUrl();
    }

    public RemoteStorageConfiguration getRemoteStorageConf() {
        return remoteStorageConf;
    }

    public void setRemoteStorageConf(RemoteStorageConfiguration remoteStorage) {
        this.remoteStorageConf = remoteStorage;
    }

    public String getBasicAuthHeader() {
        return Base64.getEncoder()
                .encodeToString((getRemoteAccountId() + ":" + getRemoteApplicationKey()).getBytes());
    }
    public String toString() {
        return ReflectionToStringBuilder.toString(this, ToStringStyle.JSON_STYLE);
    }


    public CloudFSConfiguration setBackOffMultiplier(double backOffMultiplier) {
        this.backOffMultiplier = backOffMultiplier;
        return this;
    }

    public double getBackOffMultiplier() {
        return backOffMultiplier;
    }
}
