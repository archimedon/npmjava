package com.rdnsn.b2intgr.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
public class ErrorObject implements ReadsError {

    @JsonProperty
    private Integer status;
    @JsonProperty
    private String message;
    @JsonProperty
    private String code;

    public ErrorObject() {
        super();
    }

    public String getCode() {
        return code;
    }

    public Integer getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public ReadsError setStatus(Integer status) {
        this.status = status;
        return this;
    }

    public ReadsError setCode(String code)  {
        this.code = code;
        return this;
    }

    public ReadsError setMessage(String message) {
        this.message = message;
        return this;
    }

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this, ToStringStyle.JSON_STYLE);
    }
}
