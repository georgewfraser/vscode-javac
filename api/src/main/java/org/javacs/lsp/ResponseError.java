package org.javacs.lsp;

import com.google.gson.JsonElement;

public class ResponseError {
    public int code;
    public String message;
    public JsonElement data;
}
