package com.nhs;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Buffers the request body so it can be read, inspected, and optionally replaced
 * before the request continues down the filter chain to the real servlet.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private byte[] myBody;

    public CachedBodyHttpServletRequest(HttpServletRequest theRequest) throws IOException {
        super(theRequest);
        try (var in = theRequest.getInputStream()) {
            myBody = in.readAllBytes();
        }
    }

    public byte[] getBodyBytes() {
        return myBody;
    }

    public String getBodyAsString() {
        return new String(myBody, StandardCharsets.UTF_8);
    }

    /** Replace the buffered body - subsequent reads (by the real servlet) will see this instead. */
    public void setBody(byte[] theNewBody) {
        myBody = theNewBody;
    }

    @Override
    public int getContentLength() {
        return myBody.length;
    }

    @Override
    public long getContentLengthLong() {
        return myBody.length;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(myBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener theReadListener) {
                // not needed for synchronous use
            }

            @Override
            public int read() {
                return byteArrayInputStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}