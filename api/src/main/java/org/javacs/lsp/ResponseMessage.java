package org.javacs.lsp;

import com.google.gson.JsonElement;

public class ResponseMessage {
    public String id;
    public JsonElement result;
    public ResponseError error;
}
