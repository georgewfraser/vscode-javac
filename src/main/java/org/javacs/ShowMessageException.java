/*
 * Original work Copyright (c) 2017 George W Fraser.
 * Modified work Copyright (c) 2017 Palantir Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 */

package org.javacs;

import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;

class ShowMessageException extends RuntimeException {
    private final MessageParams message;

    ShowMessageException(MessageParams message, Exception cause) {
        super(message.getMessage(), cause);

        this.message = message;
    }

    static ShowMessageException error(String message, Exception cause) {
        return create(MessageType.Error, message, cause);
    }

    static ShowMessageException warning(String message, Exception cause) {
        return create(MessageType.Warning, message, cause);
    }

    private static ShowMessageException create(MessageType type, String message, Exception cause) {
        MessageParams m = new MessageParams(type, message);

        return new ShowMessageException(m, cause);
    }
}
