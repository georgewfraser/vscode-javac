package org.javacs.lsp;

import java.util.Optional;

public class ResponseMessage {
    public final String jsonrpc = "2.0";
    public final Integer id;

    protected ResponseMessage(Integer id) {
        this.id = id;
    }

    public static class Success extends ResponseMessage {
        public final Object result;

        public Success(Integer id, Object result) {
          super(id);
          if (result instanceof Optional) {
              var option = (Optional) result;
              result = option.orElse(null);
          }
          this.result = result;
        }
    }

    public static class Error extends ResponseMessage {
        public final ResponseError error;

        public Error(Integer id, ResponseError error) {
          super(id);
          this.error = error;
        }
    }
}
